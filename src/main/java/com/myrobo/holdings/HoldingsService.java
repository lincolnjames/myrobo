package com.myrobo.holdings;

import com.myrobo.common.config.LoadedConfig;
import com.myrobo.common.config.RoboConfig;
import com.myrobo.holdings.HoldingsRepository.Cash;
import com.myrobo.holdings.HoldingsRepository.Fx;
import com.myrobo.portfolio.Valuation;
import com.myrobo.portfolio.Valuation.Position;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** 잔고 스냅샷 저장과, 화면에 보여 줄 현재 상태 조립. */
@Service
public class HoldingsService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 화면 응답. snapshotAgeDays가 7을 넘으면 주문안이 막힌다 (안전 규칙: 잔고 신선도). */
    public record PortfolioResponse(
            String broker,
            String horizon,
            List<String> coreSymbols,
            LocalDate asOf,
            Long snapshotAgeDays,
            List<Position> positions,
            Cash cash,
            Fx fx,
            Valuation.View valuation) {
    }

    private final HoldingsRepository repo;
    private final LoadedConfig loaded;

    public HoldingsService(HoldingsRepository repo, LoadedConfig loaded) {
        this.repo = repo;
        this.loaded = loaded;
    }

    public PortfolioResponse view() {
        RoboConfig c = loaded.config();
        long account = repo.coreAccountId().orElseThrow(() -> new IllegalStateException("코어 계좌가 등록되지 않음"));
        Optional<LocalDate> asOf = repo.latestAsOf(account);
        List<Position> positions = asOf.map(d -> repo.positionsAt(account, d)).orElse(List.of());
        Cash cash = asOf.map(d -> repo.cashAt(account, d)).orElse(Cash.ZERO);
        Optional<Fx> fx = repo.latestFx();

        TreeSet<String> symbols = new TreeSet<>(c.whitelist());
        Valuation.View view = Valuation.of(positions, cash.usd(), cash.krw(),
                repo.latestPrices(symbols), fx.map(Fx::rate).orElse(null),
                c.targetsFor(c.goal().horizon()), true, c.whitelist(),
                c.rebalance().toPlannerConfig());

        LocalDate today = LocalDate.now(SEOUL);
        return new PortfolioResponse(c.coreBroker(), c.goal().horizon(), List.copyOf(symbols),
                asOf.orElse(null), asOf.map(d -> ChronoUnit.DAYS.between(d, today)).orElse(null),
                positions, cash, fx.orElse(null), view);
    }

    /** 검증을 통과하면 그날 스냅샷을 통째로 바꾸고 최신 상태를 돌려준다. */
    @Transactional
    public SnapshotInput.Result save(SnapshotInput input) {
        SnapshotInput.Result r = input.validate(LocalDate.now(SEOUL));
        if (r.ok()) {
            long account = repo.coreAccountId().orElseThrow(() -> new IllegalStateException("코어 계좌가 등록되지 않음"));
            repo.replaceSnapshot(account, r.clean());
        }
        return r;
    }
}

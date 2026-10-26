package com.myrobo.holdings;

import com.myrobo.common.config.LoadedConfig;
import com.myrobo.common.config.RoboConfig;
import com.myrobo.holdings.HoldingsRepository.Cash;
import com.myrobo.holdings.HoldingsRepository.Fx;
import com.myrobo.portfolio.Valuation;
import com.myrobo.portfolio.Valuation.Position;
import com.myrobo.portfolio.Valuation.Price;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

    /**
     * 계산에 쓰는 현재 상태 한 벌. 화면과 주문안이 같은 값을 보도록 한곳에서 읽는다.
     *
     * @param asOf      마지막 잔고 기준일. 없으면 null
     * @param enteredAt 그 잔고를 저장한 시각. 없으면 null
     * @param fx        최신 환율. 없으면 null
     * @param prices    화이트리스트 종목의 최신 종가 (없는 종목은 빠져 있음)
     */
    public record State(long account, LocalDate asOf, Instant enteredAt, List<Position> positions, Cash cash,
                        Fx fx, Map<String, Price> prices) {
    }

    public State state() {
        RoboConfig c = loaded.config();
        long account = repo.coreAccountId().orElseThrow(() -> new IllegalStateException("코어 계좌가 등록되지 않음"));
        LocalDate asOf = repo.latestAsOf(account).orElse(null);
        List<Position> positions = asOf == null ? List.of() : repo.positionsAt(account, asOf);
        Cash cash = asOf == null ? Cash.ZERO : repo.cashAt(account, asOf);
        Instant enteredAt = asOf == null ? null : repo.enteredAt(account, asOf).orElse(null);
        return new State(account, asOf, enteredAt, positions, cash, repo.latestFx().orElse(null),
                repo.latestPrices(new TreeSet<>(c.whitelist())));
    }

    public PortfolioResponse view() {
        RoboConfig c = loaded.config();
        State st = state();
        TreeSet<String> symbols = new TreeSet<>(c.whitelist());
        Valuation.View view = Valuation.of(st.positions(), st.cash().usd(), st.cash().krw(),
                st.prices(), st.fx() == null ? null : st.fx().rate(),
                c.targetsFor(c.goal().horizon()), true, c.whitelist(),
                c.rebalance().toPlannerConfig());

        LocalDate today = LocalDate.now(SEOUL);
        return new PortfolioResponse(c.coreBroker(), c.goal().horizon(), List.copyOf(symbols),
                st.asOf(), st.asOf() == null ? null : ChronoUnit.DAYS.between(st.asOf(), today),
                st.positions(), st.cash(), st.fx(), view);
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

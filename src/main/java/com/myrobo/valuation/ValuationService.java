package com.myrobo.valuation;

import com.myrobo.common.config.LoadedConfig;
import com.myrobo.holdings.HoldingsRepository;
import com.myrobo.holdings.HoldingsRepository.Cash;
import com.myrobo.portfolio.Valuation.Position;
import com.myrobo.valuation.Drawdown.Alert;
import com.myrobo.valuation.Drawdown.Level;
import com.myrobo.valuation.Drawdown.Row;
import com.myrobo.valuation.PeriodReturn.Holdings;
import com.myrobo.valuation.PeriodReturn.Market;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 오늘(한국 날짜)의 평가를 한 줄 기록하고, 낙폭 경보 단계가 바뀌었으면 알릴 내용을 돌려준다.
 * 같은 날 다시 돌리면 그날 줄을 다시 계산해 덮어쓰고, 이미 알린 단계는 다시 알리지 않는다.
 */
@Service
public class ValuationService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 분할·배당을 찾을 때 직전 기록 날짜보다 이만큼 앞부터 읽는다 (종가 날짜가 기록 날짜보다 앞서므로). */
    private static final int ACTION_LOOKBACK_DAYS = 14;

    public enum Status { RECORDED, SKIPPED, FAILED }

    /** 실행 결과. 기록하지 못했으면 row는 null. */
    public record Outcome(Status status, String detail, Row row, Optional<Alert> alert) {

        static Outcome skipped(String why) {
            return new Outcome(Status.SKIPPED, why, null, Optional.empty());
        }
    }

    /** 조회 응답: 경보 단계와 최근 기록(최신순). */
    public record View(List<Level> levels, List<Row> recent) {
    }

    private final ValuationRepository repo;
    private final HoldingsRepository holdings;
    private final LoadedConfig loaded;

    public ValuationService(ValuationRepository repo, HoldingsRepository holdings, LoadedConfig loaded) {
        this.repo = repo;
        this.holdings = holdings;
        this.loaded = loaded;
    }

    public Outcome run() {
        return run(LocalDate.now(SEOUL));
    }

    public Outcome run(LocalDate today) {
        Optional<Long> account = holdings.coreAccountId();
        if (account.isEmpty()) {
            return Outcome.skipped("코어 계좌가 등록되지 않음");
        }
        Optional<LocalDate> asOf = repo.asOfOnOrBefore(account.get(), today);
        if (asOf.isEmpty()) {
            return Outcome.skipped("잔고 입력 전");
        }
        List<Level> levels = Drawdown.levels(loaded.config());
        Holdings now = load(account.get(), asOf.get());
        Optional<Row> prev = repo.previous(today);
        Holdings before = prev.flatMap(p -> repo.asOfOnOrBefore(account.get(), p.date()))
                .map(d -> load(account.get(), d)).orElse(Holdings.EMPTY);

        TreeSet<String> symbols = new TreeSet<>(now.qty().keySet());
        symbols.addAll(before.qty().keySet());
        try {
            Market marketNow = market(symbols, today);
            BigDecimal total = PeriodReturn.valueKrw(now, marketNow);
            BigDecimal ret = BigDecimal.ZERO;
            if (prev.isPresent()) {
                LocalDate then = prev.get().date();
                ret = PeriodReturn.of(before, market(symbols, then), marketNow,
                        repo.actions(symbols, then.minusDays(ACTION_LOOKBACK_DAYS), today));
            }
            Row row = Drawdown.next(prev.orElse(null), today, total, ret, levels);
            int notified = repo.levelOn(today).orElse(prev.map(Row::level).orElse(0));
            repo.upsert(row);
            return new Outcome(Status.RECORDED, "낙폭 " + row.drawdownPct() + "%, 단계 " + row.level(), row,
                    Drawdown.alert(notified, row, levels));
        } catch (PeriodReturn.MissingData e) {
            return new Outcome(Status.FAILED, e.getMessage(), null, Optional.empty());
        }
    }

    public View view(int days) {
        return new View(Drawdown.levels(loaded.config()), repo.recent(days));
    }

    private Holdings load(long account, LocalDate asOf) {
        Map<String, Long> qty = new TreeMap<>();
        for (Position p : holdings.positionsAt(account, asOf)) {
            qty.put(p.symbol(), p.qty());
        }
        Cash cash = holdings.cashAt(account, asOf);
        return new Holdings(qty, cash.krw(), cash.usd());
    }

    private Market market(TreeSet<String> symbols, LocalDate d) {
        return new Market(repo.pricesBefore(symbols, d), repo.fxBefore(d).orElse(null));
    }
}

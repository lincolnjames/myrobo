package com.myrobo.holdings;

import com.myrobo.portfolio.Valuation.Position;
import com.myrobo.portfolio.Valuation.Price;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 잔고 스냅샷, 기준 데이터(목표·계좌·종목), 평가에 필요한 최신 가격·환율. */
@Repository
public class HoldingsRepository {

    // ── 기준 데이터 ──
    static final String GOAL_FIRST = "select id from goal order by id limit 1";
    static final String GOAL_INSERT = """
            insert into goal (name, horizon, start_date, max_drawdown_pct, base_currency)
            values ('main', :horizon, :start, :dd, :currency) returning id""";
    static final String GOAL_UPDATE = """
            update goal set horizon = :horizon, start_date = :start, max_drawdown_pct = :dd, base_currency = :currency
            where id = :id""";
    static final String CORE_ACCOUNT = "select id from account where role = 'CORE' and active order by id limit 1";
    static final String ACCOUNT_INSERT = """
            insert into account (broker, role, goal_id, name) values (:broker, 'CORE', :goal, '코어') returning id""";
    static final String ACCOUNT_UPDATE = "update account set broker = :broker, goal_id = :goal where id = :id";
    static final String INSTRUMENT_UPSERT = """
            insert into instrument (symbol, type, asset_class, sleeve, active) values (:symbol, 'ETF', :assetClass, 'CORE', true)
            on conflict (symbol) do update set type = 'ETF', asset_class = excluded.asset_class, sleeve = 'CORE', active = true""";
    static final String INSTRUMENT_RETIRE =
            "update instrument set active = false where sleeve = 'CORE' and symbol not in (:symbols)";

    // ── 스냅샷 ──
    static final String LATEST_AS_OF = """
            select as_of from (
                select as_of from holding_snapshot where account_id = :account
                union select as_of from cash_balance where account_id = :account
            ) s order by as_of desc limit 1""";
    static final String POSITIONS_AT = """
            select symbol, qty, avg_price from holding_snapshot
            where account_id = :account and as_of = :asOf order by symbol""";
    static final String CASH_AT = "select currency, amount from cash_balance where account_id = :account and as_of = :asOf";
    static final String DELETE_POSITIONS = "delete from holding_snapshot where account_id = :account and as_of = :asOf";
    static final String INSERT_POSITION = """
            insert into holding_snapshot (account_id, as_of, symbol, qty, avg_price)
            values (:account, :asOf, :symbol, :qty, :avgPrice)""";
    static final String UPSERT_CASH = """
            insert into cash_balance (account_id, as_of, currency, amount) values (:account, :asOf, :currency, :amount)
            on conflict (account_id, as_of, currency) do update set amount = excluded.amount, entered_at = now()""";
    /**
     * 체결로 만든 스냅샷용. now()는 트랜잭션 시작 시각이라 같은 트랜잭션에서 주문안을 닫은 시각(updated_at)과
     * 같아지므로, 저장하는 순간의 시각(clock_timestamp)을 써서 "닫힌 뒤에 입력한 잔고"로 인정되게 한다.
     */
    static final String UPSERT_CASH_NOW = """
            insert into cash_balance (account_id, as_of, currency, amount, entered_at)
            values (:account, :asOf, :currency, :amount, clock_timestamp())
            on conflict (account_id, as_of, currency) do update
            set amount = excluded.amount, entered_at = clock_timestamp()""";
    static final String INSERT_LEDGER = """
            insert into cash_ledger (account_id, date, type, currency, amount, memo)
            values (:account, :date, :type, :currency, :amount, :memo)""";
    /** 그 기준일 잔고를 마지막으로 저장한 시각. 저장할 때마다 현금 두 줄이 함께 갱신된다. */
    static final String ENTERED_AT =
            "select max(entered_at) from cash_balance where account_id = :account and as_of = :asOf";

    // ── 평가용 ──
    static final String LATEST_PRICES = """
            select distinct on (symbol) symbol, date, close from price_daily
            where symbol in (:symbols) order by symbol, date desc""";
    static final String LATEST_FX = "select date, rate from fx_rate where pair = 'USD/KRW' order by date desc limit 1";

    public record Fx(LocalDate date, BigDecimal rate) {
    }

    public record Cash(BigDecimal krw, BigDecimal usd) {
        public static final Cash ZERO = new Cash(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final JdbcClient jdbc;

    public HoldingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── 기준 데이터 ──

    public long upsertGoal(String horizon, LocalDate start, BigDecimal maxDrawdownPct, String currency) {
        Optional<Long> id = jdbc.sql(GOAL_FIRST).query(Long.class).optional();
        if (id.isEmpty()) {
            return jdbc.sql(GOAL_INSERT).param("horizon", horizon).param("start", start)
                    .param("dd", maxDrawdownPct).param("currency", currency).query(Long.class).single();
        }
        jdbc.sql(GOAL_UPDATE).param("horizon", horizon).param("start", start).param("dd", maxDrawdownPct)
                .param("currency", currency).param("id", id.get()).update();
        return id.get();
    }

    public long upsertCoreAccount(String broker, long goalId) {
        Optional<Long> id = coreAccountId();
        if (id.isEmpty()) {
            return jdbc.sql(ACCOUNT_INSERT).param("broker", broker).param("goal", goalId).query(Long.class).single();
        }
        jdbc.sql(ACCOUNT_UPDATE).param("broker", broker).param("goal", goalId).param("id", id.get()).update();
        return id.get();
    }

    public Optional<Long> coreAccountId() {
        return jdbc.sql(CORE_ACCOUNT).query(Long.class).optional();
    }

    public void syncCoreInstruments(Map<String, String> assetClassToSymbol) {
        assetClassToSymbol.forEach((assetClass, symbol) -> jdbc.sql(INSTRUMENT_UPSERT)
                .param("symbol", symbol).param("assetClass", assetClass).update());
        jdbc.sql(INSTRUMENT_RETIRE).param("symbols", List.copyOf(assetClassToSymbol.values())).update();
    }

    // ── 스냅샷 ──

    public Optional<LocalDate> latestAsOf(long account) {
        return jdbc.sql(LATEST_AS_OF).param("account", account).query(LocalDate.class).optional();
    }

    public List<Position> positionsAt(long account, LocalDate asOf) {
        return jdbc.sql(POSITIONS_AT).param("account", account).param("asOf", asOf)
                .query((rs, n) -> new Position(rs.getString("symbol"), rs.getLong("qty"), rs.getBigDecimal("avg_price")))
                .list();
    }

    public Optional<java.time.Instant> enteredAt(long account, LocalDate asOf) {
        return jdbc.sql(ENTERED_AT).param("account", account).param("asOf", asOf)
                .query((rs, n) -> {
                    java.time.OffsetDateTime t = rs.getObject(1, java.time.OffsetDateTime.class);
                    return t == null ? null : t.toInstant();
                })
                .optional();
    }

    public Cash cashAt(long account, LocalDate asOf) {
        Map<String, BigDecimal> m = new HashMap<>();
        jdbc.sql(CASH_AT).param("account", account).param("asOf", asOf)
                .query((rs, n) -> Map.entry(rs.getString("currency").trim(), rs.getBigDecimal("amount")))
                .list()
                .forEach(e -> m.put(e.getKey(), e.getValue()));
        return new Cash(m.getOrDefault("KRW", BigDecimal.ZERO), m.getOrDefault("USD", BigDecimal.ZERO));
    }

    /** 그날 스냅샷을 통째로 바꾼다. 호출하는 쪽이 트랜잭션으로 감싼다. */
    public void replaceSnapshot(long account, SnapshotInput.Clean s) {
        jdbc.sql(DELETE_POSITIONS).param("account", account).param("asOf", s.asOf()).update();
        for (SnapshotInput.Line l : s.holdings()) {
            jdbc.sql(INSERT_POSITION).param("account", account).param("asOf", s.asOf())
                    .param("symbol", l.symbol()).param("qty", l.qty()).param("avgPrice", l.avgPrice()).update();
        }
        upsertCash(account, s.asOf(), "KRW", s.cashKrw());
        upsertCash(account, s.asOf(), "USD", s.cashUsd());
    }

    /** {@link #replaceSnapshot}과 같지만 저장 시각을 clock_timestamp()로 남긴다 (체결 입력용). */
    public void replaceSnapshotAfterClose(long account, SnapshotInput.Clean s) {
        jdbc.sql(DELETE_POSITIONS).param("account", account).param("asOf", s.asOf()).update();
        for (SnapshotInput.Line l : s.holdings()) {
            jdbc.sql(INSERT_POSITION).param("account", account).param("asOf", s.asOf())
                    .param("symbol", l.symbol()).param("qty", l.qty()).param("avgPrice", l.avgPrice()).update();
        }
        upsertCashNow(account, s.asOf(), "KRW", s.cashKrw());
        upsertCashNow(account, s.asOf(), "USD", s.cashUsd());
    }

    public void insertLedger(long account, LocalDate date, String type, String currency, BigDecimal amount,
                             String memo) {
        jdbc.sql(INSERT_LEDGER).param("account", account).param("date", date).param("type", type)
                .param("currency", currency).param("amount", amount).param("memo", memo).update();
    }

    private void upsertCashNow(long account, LocalDate asOf, String currency, BigDecimal amount) {
        jdbc.sql(UPSERT_CASH_NOW).param("account", account).param("asOf", asOf)
                .param("currency", currency).param("amount", amount).update();
    }

    private void upsertCash(long account, LocalDate asOf, String currency, BigDecimal amount) {
        jdbc.sql(UPSERT_CASH).param("account", account).param("asOf", asOf)
                .param("currency", currency).param("amount", amount).update();
    }

    // ── 평가용 ──

    public Map<String, Price> latestPrices(Collection<String> symbols) {
        Map<String, Price> out = new HashMap<>();
        if (symbols.isEmpty()) {
            return out;
        }
        jdbc.sql(LATEST_PRICES).param("symbols", List.copyOf(symbols))
                .query((rs, n) -> Map.entry(rs.getString("symbol"),
                        new Price(rs.getBigDecimal("close"), rs.getObject("date", LocalDate.class))))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public Optional<Fx> latestFx() {
        return jdbc.sql(LATEST_FX)
                .query((rs, n) -> new Fx(rs.getObject("date", LocalDate.class), rs.getBigDecimal("rate")))
                .optional();
    }
}

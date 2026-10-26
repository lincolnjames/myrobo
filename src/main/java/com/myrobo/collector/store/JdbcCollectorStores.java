package com.myrobo.collector.store;

import com.myrobo.collector.CollectorStores.FxStore;
import com.myrobo.collector.CollectorStores.HolidayStore;
import com.myrobo.collector.CollectorStores.PriceStore;
import com.myrobo.collector.CollectorTypes.DailyBar;
import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.CollectorTypes.MarketHoliday;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 수집 저장소의 Postgres 구현. 모든 쓰기는 upsert(ON CONFLICT … DO UPDATE)라 다시 실행해도 안전하다.
 * 일봉은 처음 받을 때 종목당 수천 줄이라 배치로 보낸다 (한 줄씩 보내면 원격 DB 왕복 때문에 수 분 걸림).
 */
public final class JdbcCollectorStores {

    private JdbcCollectorStores() {
    }

    public static final String SOURCE_TIINGO = "TIINGO";
    static final int BATCH = 1000;

    static final String LAST_DATE =
            "select date from price_daily where symbol = :symbol order by date desc limit 1";

    static final String UPSERT_BAR = """
            insert into price_daily (symbol, date, open, high, low, close, adj_close, volume, source)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (symbol, date) do update set
                open = excluded.open, high = excluded.high, low = excluded.low, close = excluded.close,
                adj_close = excluded.adj_close, volume = excluded.volume, source = excluded.source""";

    static final String UPSERT_ACTION = """
            insert into corporate_action (symbol, ex_date, type, ratio, amount)
            values (?, ?, ?, ?, ?)
            on conflict (symbol, ex_date, type) do update set
                ratio = excluded.ratio, amount = excluded.amount""";

    static final String UPSERT_HOLIDAY = """
            insert into market_holiday (exchange, date, event_name, trading_hours, full_day_closed, updated_at)
            values ('US', ?, ?, ?, ?, now())
            on conflict (exchange, date) do update set
                event_name = excluded.event_name, trading_hours = excluded.trading_hours,
                full_day_closed = excluded.full_day_closed, updated_at = now()""";

    static final String CLOSED_DAYS =
            "select date from market_holiday where exchange = 'US' and full_day_closed";

    static final String UPSERT_FX = """
            insert into fx_rate (date, pair, rate, source)
            values (:date, 'USD/KRW', :rate, :source)
            on conflict (date, pair) do update set rate = excluded.rate, source = excluded.source""";

    public static final class Prices implements PriceStore {
        private final JdbcClient jdbc;
        private final JdbcTemplate template;

        public Prices(JdbcClient jdbc, JdbcTemplate template) {
            this.jdbc = jdbc;
            this.template = template;
        }

        @Override
        public Optional<LocalDate> lastDate(String symbol) {
            return jdbc.sql(LAST_DATE).param("symbol", symbol).query(LocalDate.class).optional();
        }

        @Override
        public void saveBars(List<DailyBar> bars) {
            List<Object[]> rows = new ArrayList<>(bars.size());
            for (DailyBar b : bars) {
                rows.add(new Object[] {b.symbol(), b.date(), b.open(), b.high(), b.low(), b.close(),
                        b.adjClose(), b.volume(), SOURCE_TIINGO});
            }
            batch(template, UPSERT_BAR, rows);
        }

        @Override
        public void saveCorporateActions(List<DailyBar> bars) {
            List<Object[]> rows = new ArrayList<>();
            for (DailyBar b : bars) {
                if (b.isSplit()) {
                    rows.add(new Object[] {b.symbol(), b.date(), "SPLIT", b.splitFactor(), null});
                }
                if (b.isDividend()) {
                    rows.add(new Object[] {b.symbol(), b.date(), "DIVIDEND", null, b.divCash()});
                }
            }
            batch(template, UPSERT_ACTION, rows);
        }
    }

    public static final class Holidays implements HolidayStore {
        private final JdbcClient jdbc;
        private final JdbcTemplate template;

        public Holidays(JdbcClient jdbc, JdbcTemplate template) {
            this.jdbc = jdbc;
            this.template = template;
        }

        @Override
        public void save(List<MarketHoliday> holidays) {
            List<Object[]> rows = new ArrayList<>();
            for (MarketHoliday h : holidays) {
                rows.add(new Object[] {h.date(), h.eventName(), h.tradingHours(), h.fullDayClosed()});
            }
            batch(template, UPSERT_HOLIDAY, rows);
        }

        @Override
        public Set<LocalDate> fullDayClosures() {
            return new HashSet<>(jdbc.sql(CLOSED_DAYS).query(LocalDate.class).list());
        }
    }

    public static final class Fx implements FxStore {
        private final JdbcClient jdbc;

        public Fx(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void save(FxQuote q) {
            jdbc.sql(UPSERT_FX)
                    .param("date", q.date())
                    .param("rate", q.rate())
                    .param("source", q.source())
                    .update();
        }
    }

    private static void batch(JdbcTemplate template, String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += BATCH) {
            template.batchUpdate(sql, rows.subList(from, Math.min(rows.size(), from + BATCH)));
        }
    }
}

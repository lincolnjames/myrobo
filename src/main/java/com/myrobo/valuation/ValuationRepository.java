package com.myrobo.valuation;

import com.myrobo.portfolio.Valuation.Price;
import com.myrobo.valuation.Drawdown.Row;
import com.myrobo.valuation.PeriodReturn.Action;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 평가 기록(valuation_daily)과, 특정 날짜 시점의 잔고·가격·환율.
 *
 * <p>가격과 환율은 기록 날짜(한국 날짜) <b>전날까지</b>의 마지막 값을 쓴다. 08:00에 도는 매일 작업이 그때 볼 수
 * 있는 값과 같게 맞춰야, 다음 날 같은 날짜를 다시 조회했을 때(그사이 그날 종가가 들어왔어도) 이어 붙이는 구간이
 * 빠지거나 겹치지 않는다.
 */
@Repository
public class ValuationRepository {

    static final String AS_OF_ON_OR_BEFORE = """
            select as_of from (
                select as_of from holding_snapshot where account_id = :account and as_of <= :d
                union select as_of from cash_balance where account_id = :account and as_of <= :d
            ) s order by as_of desc limit 1""";
    static final String PRICES_BEFORE = """
            select distinct on (symbol) symbol, date, close from price_daily
            where symbol in (:symbols) and date < :d order by symbol, date desc""";
    static final String FX_BEFORE =
            "select rate from fx_rate where pair = 'USD/KRW' and date < :d order by date desc limit 1";
    static final String ACTIONS = """
            select symbol, ex_date, type, ratio, amount from corporate_action
            where symbol in (:symbols) and ex_date > :from and ex_date <= :to""";
    static final String PREVIOUS = "select * from valuation_daily where date < :d order by date desc limit 1";
    static final String LEVEL_ON = "select alert_level from valuation_daily where date = :d";
    static final String RECENT = "select * from valuation_daily order by date desc limit :n";
    static final String UPSERT = """
            insert into valuation_daily (date, total_krw, return_pct, nav, peak_nav, peak_date, drawdown_pct, alert_level)
            values (:date, :total, :ret, :nav, :peak, :peakDate, :dd, :level)
            on conflict (date) do update set
                total_krw = excluded.total_krw, return_pct = excluded.return_pct, nav = excluded.nav,
                peak_nav = excluded.peak_nav, peak_date = excluded.peak_date, drawdown_pct = excluded.drawdown_pct,
                alert_level = excluded.alert_level, computed_at = now()""";

    private final JdbcClient jdbc;

    public ValuationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 날짜까지 입력된 마지막 잔고 기준일. */
    public Optional<LocalDate> asOfOnOrBefore(long account, LocalDate d) {
        return jdbc.sql(AS_OF_ON_OR_BEFORE).param("account", account).param("d", d).query(LocalDate.class).optional();
    }

    public Map<String, Price> pricesBefore(Collection<String> symbols, LocalDate d) {
        Map<String, Price> out = new HashMap<>();
        if (symbols.isEmpty()) {
            return out;
        }
        jdbc.sql(PRICES_BEFORE).param("symbols", List.copyOf(symbols)).param("d", d)
                .query((rs, n) -> Map.entry(rs.getString("symbol"),
                        new Price(rs.getBigDecimal("close"), rs.getObject("date", LocalDate.class))))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public Optional<BigDecimal> fxBefore(LocalDate d) {
        return jdbc.sql(FX_BEFORE).param("d", d).query(BigDecimal.class).optional();
    }

    public List<Action> actions(Collection<String> symbols, LocalDate from, LocalDate to) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ACTIONS).param("symbols", List.copyOf(symbols)).param("from", from).param("to", to)
                .query((rs, n) -> new Action(rs.getString("symbol"), rs.getObject("ex_date", LocalDate.class),
                        rs.getString("type"), rs.getBigDecimal("ratio"), rs.getBigDecimal("amount")))
                .list();
    }

    public Optional<Row> previous(LocalDate d) {
        return jdbc.sql(PREVIOUS).param("d", d).query((rs, n) -> row(rs)).optional();
    }

    public Optional<Integer> levelOn(LocalDate d) {
        return jdbc.sql(LEVEL_ON).param("d", d).query(Integer.class).optional();
    }

    public List<Row> recent(int n) {
        return jdbc.sql(RECENT).param("n", n).query((rs, i) -> row(rs)).list();
    }

    public void upsert(Row r) {
        jdbc.sql(UPSERT)
                .param("date", r.date()).param("total", r.totalKrw()).param("ret", r.returnPct())
                .param("nav", r.nav()).param("peak", r.peakNav()).param("peakDate", r.peakDate())
                .param("dd", r.drawdownPct()).param("level", r.level())
                .update();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("date", LocalDate.class), rs.getBigDecimal("total_krw"),
                rs.getBigDecimal("return_pct"), rs.getBigDecimal("nav"), rs.getBigDecimal("peak_nav"),
                rs.getObject("peak_date", LocalDate.class), rs.getBigDecimal("drawdown_pct"), rs.getInt("alert_level"));
    }
}

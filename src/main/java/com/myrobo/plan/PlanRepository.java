package com.myrobo.plan;

import com.myrobo.guard.SafetyGuard;
import com.myrobo.plan.PlanViews.CheckView;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;
import com.myrobo.portfolio.PlanTypes.OrderLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 주문안·주문 줄·검증 결과 저장. 상태 전이는 "지금 상태가 X일 때만"으로 걸어 경쟁 상태에서도 꼬이지 않게 한다. */
@Repository
public class PlanRepository {

    /** 계좌별 주문안 생성 직렬화. 트랜잭션이 끝나면 자동으로 풀린다. */
    static final String LOCK = "select pg_advisory_xact_lock(770100 + :account)";
    static final String HOLIDAYS = "select date from market_holiday where full_day_closed";
    static final String EXPIRE_OVERDUE = """
            update order_plan set status = 'EXPIRED', updated_at = now()
            where account_id = :account and status = 'SENT' and expires_at < :tradeDate""";
    static final String FIND_OPEN = """
            select id from order_plan where account_id = :account and status in ('DRAFT', 'SENT')
            order by id limit 1""";
    static final String FIND_BY_KEY = "select id from order_plan where idempotency_key = :key";
    static final String LAST_CLOSED = """
            select max(updated_at) from order_plan where account_id = :account and status in ('DONE', 'EXPIRED')""";
    static final String INSERT_PLAN = """
            insert into order_plan (account_id, plan_date, type, status, idempotency_key, rule_version, action,
                                    reason, note, total_amount, size_pct, fx_usd, fx_krw_est, expires_at)
            values (:account, :planDate, :type, 'DRAFT', :key, :version, :action,
                    :reason, :note, :total, :size, :fxUsd, :fxKrw, :expiresAt)
            returning id""";
    static final String INSERT_ITEM = """
            insert into order_plan_item (plan_id, side, symbol, qty, est_price, est_amount, currency)
            values (:plan, :side, :symbol, :qty, :price, :amount, 'USD')""";
    static final String INSERT_CHECK = """
            insert into guard_result (plan_id, rule, passed, detail) values (:plan, :rule, :passed, :detail)""";
    static final String INSERT_EXECUTION = """
            insert into execution (plan_item_id, executed_at, qty, price, fee, currency)
            values (:item, :at, :qty, :price, :fee, 'USD')""";
    static final String SET_STATUS = """
            update order_plan set status = :to, updated_at = now() where id = :id and status = :from""";
    static final String SELECT_PLANS = """
            select id, plan_date, type, status, action, reason, note, total_amount, size_pct, fx_usd, fx_krw_est,
                   expires_at, created_at
            from order_plan""";
    static final String RECENT = SELECT_PLANS
            + " where account_id = :account order by created_at desc, id desc limit :limit";
    static final String BY_ID = SELECT_PLANS + " where id = :id";
    static final String ITEMS = """
            select id, plan_id, side, symbol, qty, est_price, est_amount from order_plan_item
            where plan_id in (:ids) order by plan_id, id""";
    static final String CHECKS = "select plan_id, rule, passed, detail from guard_result where plan_id in (:ids)";

    /** 새로 저장할 주문안 한 건. */
    public record NewPlan(long account, LocalDate tradeDate, String type, String key, String ruleVersion,
                          String action, String reason, String note, BigDecimal totalUsd, BigDecimal sizePct,
                          Long fxUsd, Long fxKrwEst, LocalDate expiresAt, List<OrderLine> lines,
                          Map<String, BigDecimal> prices) {
    }

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(long account) {
        jdbc.sql(LOCK).param("account", account).query((rs, n) -> 1).single();
    }

    public Set<LocalDate> fullDayClosures() {
        return new HashSet<>(jdbc.sql(HOLIDAYS).query((rs, n) -> rs.getObject(1, LocalDate.class)).list());
    }

    /** 유효 기간이 지난 발송 주문안을 만료 처리하고 몇 건인지 돌려준다. */
    public int expireOverdue(long account, LocalDate tradeDate) {
        return jdbc.sql(EXPIRE_OVERDUE).param("account", account).param("tradeDate", tradeDate).update();
    }

    public Optional<Long> findOpen(long account) {
        return jdbc.sql(FIND_OPEN).param("account", account).query(Long.class).optional();
    }

    public Optional<Long> findByKey(String key) {
        return jdbc.sql(FIND_BY_KEY).param("key", key).query(Long.class).optional();
    }

    public Optional<Instant> lastClosedAt(long account) {
        return jdbc.sql(LAST_CLOSED).param("account", account)
                .query((rs, n) -> {
                    OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
                    return t == null ? null : t.toInstant();
                })
                .optional();
    }

    /** DRAFT로 저장하고 id를 돌려준다. 상태는 검증 결과를 저장한 뒤 {@link #transition}으로 바꾼다. */
    public long insertDraft(NewPlan p) {
        long id = jdbc.sql(INSERT_PLAN)
                .param("account", p.account()).param("planDate", p.tradeDate()).param("type", p.type())
                .param("key", p.key()).param("version", p.ruleVersion()).param("action", p.action())
                .param("reason", p.reason()).param("note", p.note()).param("total", p.totalUsd())
                .param("size", p.sizePct()).param("fxUsd", p.fxUsd()).param("fxKrw", p.fxKrwEst())
                .param("expiresAt", p.expiresAt())
                .query(Long.class).single();
        for (OrderLine l : p.lines()) {
            BigDecimal price = p.prices().get(l.symbol()).setScale(6, RoundingMode.HALF_UP);
            jdbc.sql(INSERT_ITEM).param("plan", id).param("side", l.side().name()).param("symbol", l.symbol())
                    .param("qty", l.qty()).param("price", price).param("amount", l.estAmountUsd()).update();
        }
        return id;
    }

    public void insertChecks(long plan, List<SafetyGuard.Check> checks) {
        for (SafetyGuard.Check c : checks) {
            jdbc.sql(INSERT_CHECK).param("plan", plan).param("rule", c.rule().name())
                    .param("passed", c.passed()).param("detail", c.detail()).update();
        }
    }

    /** 실제 체결 한 줄 저장. 미체결(0주)은 저장하지 않는다 (execution.qty > 0). */
    public void insertExecution(ExecutionInput.Executed e, OffsetDateTime at) {
        jdbc.sql(INSERT_EXECUTION).param("item", e.itemId()).param("at", at).param("qty", e.qty())
                .param("price", e.price()).param("fee", e.fee()).update();
    }

    /** from 상태일 때만 to로 바꾼다. 다른 상태였으면 예외 (트랜잭션 전체가 되돌려진다). */
    public void transition(long id, String from, String to) {
        int n = jdbc.sql(SET_STATUS).param("id", id).param("from", from).param("to", to).update();
        if (n != 1) {
            throw new IllegalStateException("주문안 #" + id + " 상태가 " + from + "가 아니라 " + to + "로 바꿀 수 없음");
        }
    }

    public Optional<PlanView> load(long id) {
        List<PlanView> one = withDetails(jdbc.sql(BY_ID).param("id", id).query(this::row).list());
        return one.stream().findFirst();
    }

    public List<PlanView> recent(long account, int limit) {
        return withDetails(jdbc.sql(RECENT).param("account", account).param("limit", limit).query(this::row).list());
    }

    // ── 행 변환 ──

    private record Row(long id, LocalDate planDate, String type, String status, String action, String reason,
                       String note, BigDecimal total, BigDecimal size, Long fxUsd, Long fxKrw, LocalDate expiresAt,
                       OffsetDateTime createdAt) {
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("id"), rs.getObject("plan_date", LocalDate.class), rs.getString("type"),
                rs.getString("status"), rs.getString("action"), rs.getString("reason"), rs.getString("note"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("size_pct"),
                rs.getObject("fx_usd", Long.class), rs.getObject("fx_krw_est", Long.class),
                rs.getObject("expires_at", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private List<PlanView> withDetails(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Row::id).toList();
        Map<Long, List<ItemView>> items = new LinkedHashMap<>();
        jdbc.sql(ITEMS).param("ids", ids).query((rs, n) -> {
            items.computeIfAbsent(rs.getLong("plan_id"), k -> new ArrayList<>()).add(new ItemView(
                    rs.getLong("id"), rs.getString("side"), rs.getString("symbol"), rs.getLong("qty"),
                    rs.getBigDecimal("est_price"), rs.getBigDecimal("est_amount")));
            return 1;
        }).list();
        Map<Long, List<CheckView>> checks = new LinkedHashMap<>();
        jdbc.sql(CHECKS).param("ids", ids).query((rs, n) -> {
            checks.computeIfAbsent(rs.getLong("plan_id"), k -> new ArrayList<>()).add(checkView(
                    rs.getString("rule"), rs.getBoolean("passed"), rs.getString("detail")));
            return 1;
        }).list();
        return rows.stream().map(r -> new PlanView(r.id(), r.planDate(), r.type(), r.status(), r.action(),
                r.reason(), r.note(), r.total(), r.size(), r.fxUsd(), r.fxKrw(), r.expiresAt(), r.createdAt(),
                List.copyOf(items.getOrDefault(r.id(), List.of())),
                checks.getOrDefault(r.id(), List.of()).stream().sorted(Comparator.comparingInt(PlanRepository::order))
                        .toList()))
                .toList();
    }

    static CheckView checkView(String rule, boolean passed, String detail) {
        String label;
        try {
            label = SafetyGuard.Rule.valueOf(rule).label();
        } catch (IllegalArgumentException e) {
            label = rule; // 예전 버전이 남긴 규칙
        }
        return new CheckView(rule, label, passed, detail);
    }

    private static int order(CheckView c) {
        try {
            return SafetyGuard.Rule.valueOf(c.rule()).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }
}

package com.myrobo.broker;

import com.myrobo.broker.OrderPlanner.Order;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** broker_dispatch·broker_order 저장 (V5, V6). */
public class JdbcDispatchStore implements DispatchStore {

    static final String ATTEMPTS = """
            select id, phase, status, cast(created_at at time zone 'Asia/Seoul' as date) as day
            from broker_dispatch where plan_id = :plan and mode = :mode order by id""";
    static final String START = """
            insert into broker_dispatch (plan_id, mode, phase, status) values (:plan, :mode, :phase, 'PENDING')
            on conflict (plan_id, mode, phase) do nothing
            returning id""";
    static final String ADD_ORDER = """
            insert into broker_order (dispatch_id, plan_item_id, side, symbol, exchange, qty, limit_price, status)
            values (:dispatch, :item, :side, :symbol, :exchange, :qty, :limit, :status)
            returning id""";
    /** 모의 예약주문 응답에는 접수일자가 없다(ODNO만, 2026-11-02 확인). 취소할 때 필요하므로 접수됐으면 오늘 한국 날짜로 채운다. */
    static final String ORDER_RESULT = """
            update broker_order set status = :status, broker_order_no = :no,
                   broker_order_dt = case when :status = 'SUBMITTED'
                                          then coalesce(cast(:dt as text), to_char(now() at time zone 'Asia/Seoul', 'YYYYMMDD'))
                                     end,
                   rt_cd = :rt, msg_cd = :msgCd, message = :msg, response = :raw, updated_at = now()
            where id = :id""";
    static final String CANCEL_RESULT = """
            update broker_order set status = :status, message = :msg, updated_at = now() where id = :id""";
    static final String FINISH = """
            update broker_dispatch set status = :status, message = :msg, updated_at = now() where id = :id""";
    static final String SUBMITTED_ON = """
            select coalesce(sum(o.qty * o.limit_price), 0) from broker_order o
            join broker_dispatch d on d.id = o.dispatch_id
            where d.mode <> 'DRY_RUN' and o.status = 'SUBMITTED'
              and cast(o.created_at at time zone 'Asia/Seoul' as date) = :day""";

    private final JdbcClient jdbc;

    public JdbcDispatchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Attempt> attempts(long planId, Mode mode) {
        return jdbc.sql(ATTEMPTS).param("plan", planId).param("mode", mode.name())
                .query((rs, n) -> new Attempt(rs.getLong("id"), Phase.valueOf(rs.getString("phase")),
                        rs.getString("status"), rs.getObject("day", LocalDate.class)))
                .list();
    }

    @Override
    public Optional<Long> start(long planId, Mode mode, Phase phase) {
        return jdbc.sql(START).param("plan", planId).param("mode", mode.name()).param("phase", phase.name())
                .query(Long.class).optional();
    }

    @Override
    public long addOrder(long dispatchId, Order o, String status) {
        return jdbc.sql(ADD_ORDER).param("dispatch", dispatchId).param("item", o.itemId()).param("side", o.side().name())
                .param("symbol", o.symbol()).param("exchange", o.exchange()).param("qty", o.qty())
                .param("limit", o.limitPrice()).param("status", status).query(Long.class).single();
    }

    @Override
    public void orderResult(long orderId, String status, KisClient.Reply r) {
        jdbc.sql(ORDER_RESULT).param("id", orderId).param("status", status)
                .param("no", r == null ? null : r.field("odno"))
                .param("dt", r == null ? null : r.field("rsvn_ord_rcit_dt"))
                .param("rt", r == null ? null : r.rtCd()).param("msgCd", r == null ? null : r.msgCd())
                .param("msg", r == null ? null : r.message()).param("raw", r == null ? null : r.raw())
                .update();
    }

    @Override
    public void cancelResult(long orderId, String status, KisClient.Reply r) {
        jdbc.sql(CANCEL_RESULT).param("id", orderId).param("status", status)
                .param("msg", "취소: " + (r == null ? "" : r.summary())).update();
    }

    @Override
    public void finish(long dispatchId, String status, String message) {
        jdbc.sql(FINISH).param("id", dispatchId).param("status", status).param("msg", message).update();
    }

    @Override
    public BigDecimal submittedUsdOn(LocalDate kstDay) {
        return jdbc.sql(SUBMITTED_ON).param("day", kstDay).query(BigDecimal.class).single();
    }
}

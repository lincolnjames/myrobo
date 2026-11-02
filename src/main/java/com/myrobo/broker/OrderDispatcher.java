package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.broker.OrderPlanner.Order;
import com.myrobo.broker.OrderPlanner.Side;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.ops.Notifier;
import com.myrobo.plan.PlanViews.PlanView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 발송된 주문안을 증권사 예약주문으로 보낸다. 앱이 켜져 있는 동안 주기적으로 {@link #tick()}이 불린다.
 *
 * <p>순서: 미완료 SENT 주문안 → 이미 보낸 적 있으면 끝 → 예약 접수 시간인지 → 주문 줄·지정가·한도
 * → 매수 가능 금액 → 시도 기록(PENDING)을 먼저 남김 → 매도부터 하나씩 접수.
 * <ul>
 *   <li>한 주문안은 모드별로 단 한 번만 시도한다 (DB unique). 실패·차단돼도 자동으로 다시 보내지 않는다</li>
 *   <li>한 줄이라도 접수에 실패하면 나머지는 보내지 않고 알린다. 이미 접수된 줄은 사람이 증권사 앱에서 확인한다</li>
 *   <li>드라이런은 조회(토큰·매수 가능 금액)까지만 하고 주문은 보내지 않는다. 보낼 내용을 기록하고 알린다</li>
 *   <li>접수 시간 밖이면 조용히 기다린다. 조회 실패 같은 일시적인 문제는 기록하지 않고 다음 틱에 다시 해 본다
 *       (알림은 주문안마다 한 번)</li>
 * </ul>
 */
public final class OrderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OrderDispatcher.class);

    public enum Outcome { OFF, NO_PLAN, ALREADY, WAITING, BLOCKED, DRY_RUN, SUBMITTED, FAILED, RETRY_LATER }

    /** 미완료 주문안과 휴장일 달력. */
    public interface PlanSource {
        Optional<PlanView> openPlan();

        MarketCalendar calendar();
    }

    private final Mode mode;
    private final Supplier<KisClient> kis;
    private final DispatchStore store;
    private final PlanSource plans;
    private final Notifier notifier;
    private final OrderPlanner.Limits limits;
    private final Clock clock;
    private final Set<Long> warned = new HashSet<>();

    /** @param mode null이면 꺼짐 */
    public OrderDispatcher(Mode mode, Supplier<KisClient> kis, DispatchStore store, PlanSource plans, Notifier notifier,
                           OrderPlanner.Limits limits, Clock clock) {
        this.mode = mode;
        this.kis = kis;
        this.store = store;
        this.plans = plans;
        this.notifier = notifier;
        this.limits = limits;
        this.clock = clock;
    }

    public synchronized Outcome tick() {
        if (mode == null) {
            return Outcome.OFF;
        }
        Optional<PlanView> open = plans.openPlan();
        if (open.isEmpty() || !"SENT".equals(open.get().status())) {
            return Outcome.NO_PLAN;
        }
        PlanView p = open.get();
        if (store.status(p.id(), mode).isPresent()) {
            return Outcome.ALREADY;
        }
        Instant now = clock.instant();
        ReservationWindow.Decision w = ReservationWindow.check(now, plans.calendar(), p.tradeDate(), p.expiresAt());
        if (!w.open()) {
            log.debug("주문안 #{} 예약 대기: {}", p.id(), w.reason());
            return Outcome.WAITING;
        }

        LocalDate today = now.atZone(ReservationWindow.SEOUL).toLocalDate();
        OrderPlanner.Result planned = OrderPlanner.plan(p, limits, store.submittedUsdOn(today));
        List<String> problems = new ArrayList<>(planned.problems());
        if (planned.ok()) {
            try {
                fundsProblem(planned).ifPresent(problems::add);
            } catch (RuntimeException e) {
                return retryLater(p, "매수 가능 금액 조회 실패: " + e.getMessage());
            }
        }

        Optional<Long> started = store.start(p.id(), mode);
        if (started.isEmpty()) {
            return Outcome.ALREADY;
        }
        long dispatch = started.get();
        if (!problems.isEmpty()) {
            store.finish(dispatch, "BLOCKED", String.join(" / ", problems));
            log.warn("주문안 #{} 전송 차단: {}", p.id(), problems);
            notifier.send(DispatchMessages.blocked(p, problems));
            return Outcome.BLOCKED;
        }
        if (mode == Mode.DRY_RUN) {
            planned.orders().forEach(o -> store.addOrder(dispatch, o, "PLANNED"));
            store.finish(dispatch, "DRY_RUN", null);
            log.info("주문안 #{} 드라이런: {}건 기록", p.id(), planned.orders().size());
            notifier.send(DispatchMessages.dryRun(p, planned.orders()));
            return Outcome.DRY_RUN;
        }
        return send(p, dispatch, planned.orders());
    }

    private Outcome send(PlanView p, long dispatch, List<Order> orders) {
        KisClient client = kis.get();
        int done = 0;
        for (Order o : orders) {
            long row = store.addOrder(dispatch, o, "SENDING");
            KisClient.Reply r;
            try {
                r = client.reserve(o.side(), o.exchange(), o.symbol(), o.qty(), o.limitPrice());
            } catch (RuntimeException e) {
                r = new KisClient.Reply(0, null, null, "전송 오류: " + e.getMessage(), Map.of(), null);
            }
            store.orderResult(row, r.ok() ? "SUBMITTED" : "FAILED", r);
            if (!r.ok()) {
                String why = o.symbol() + " " + label(o.side()) + " 접수 실패: " + r.summary();
                store.finish(dispatch, "FAILED", why + " (앞서 " + done + "건 접수됨, 나머지는 보내지 않음)");
                log.error("주문안 #{} 예약주문 실패: {}", p.id(), why);
                notifier.send(DispatchMessages.failed(p, orders.subList(0, done), o, r.summary(), orders.size() - done - 1));
                return Outcome.FAILED;
            }
            done++;
        }
        store.finish(dispatch, "SUBMITTED", null);
        log.info("주문안 #{} 예약주문 {}건 접수", p.id(), done);
        notifier.send(DispatchMessages.submitted(p, orders));
        return Outcome.SUBMITTED;
    }

    /** 매수 합계가 지금 쓸 수 있는 달러보다 크면 문제. 매도 대금은 아직 세지 않는다 (모의에서 재사용 여부 확인 전). */
    private Optional<String> fundsProblem(OrderPlanner.Result planned) {
        Optional<Order> firstBuy = planned.orders().stream().filter(o -> o.side() == Side.BUY).findFirst();
        if (firstBuy.isEmpty()) {
            return Optional.empty();
        }
        Order b = firstBuy.get();
        BigDecimal need = planned.total(Side.BUY);
        BigDecimal have = kis.get().buyable(b.exchange(), b.symbol(), b.limitPrice()).orderableUsd();
        return need.compareTo(have) > 0
                ? Optional.of("매수 합계 $" + OrderPlanner.money(need) + "가 매수 가능 금액 $" + OrderPlanner.money(have) + "보다 큼")
                : Optional.empty();
    }

    private Outcome retryLater(PlanView p, String why) {
        log.warn("주문안 #{} 전송 보류, 다음에 다시 시도: {}", p.id(), why);
        if (warned.add(p.id())) {
            notifier.send(DispatchMessages.retryLater(p, why));
        }
        return Outcome.RETRY_LATER;
    }

    static String label(Side s) {
        return s == Side.BUY ? "매수" : "매도";
    }
}

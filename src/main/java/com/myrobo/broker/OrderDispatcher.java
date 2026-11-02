package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Attempt;
import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.broker.DispatchStore.Phase;
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
import java.time.format.DateTimeFormatter;
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
 * <p>순서: 미완료 SENT 주문안 → 예약 접수 시간인지 → 주문 줄·지정가·한도 → 매수 가능 금액으로 단계 결정
 * → 시도 기록(PENDING)을 먼저 남김 → 하나씩 접수.
 * <ul>
 *   <li>단계: 지금 현금으로 매수가 되면 한 번에(ALL, 매도 먼저). 모자라지만 매도 대금을 더하면 되면 오늘은 매도만(SELL),
 *       다음 거래일 이후 매수 가능 금액이 충분해지면 매수(BUY). 예약 매도·매수는 같은 장 시작에 함께 나가므로
 *       매도 대금으로 같은 날 매수할 수 있다고 기대하지 않는다. 그래도 모자라면 차단</li>
 *   <li>한 주문안은 모드·단계마다 단 한 번만 시도한다 (DB unique). 실패·차단돼도 자동으로 다시 보내지 않는다</li>
 *   <li>한 줄이라도 접수에 실패하면 나머지는 보내지 않고, 이번 시도에서 이미 접수된 줄은 예약 취소한다
 *       (일부만 실행되지 않게). 취소까지 실패한 줄은 알림에 적어 사람이 증권사 앱에서 취소한다</li>
 *   <li>드라이런은 조회(토큰·매수 가능 금액)까지만 하고 주문은 보내지 않는다. 보낼 내용을 기록하고 알린다</li>
 *   <li>접수 시간 밖이면 조용히 기다린다. 조회 실패·매도 대금 대기는 기록하지 않고 다음 틱에 다시 본다
 *       (알림은 주문안마다 한 번)</li>
 * </ul>
 */
public final class OrderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OrderDispatcher.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    public enum Outcome {
        OFF, NO_PLAN, ALREADY, WAITING, BLOCKED, DRY_RUN, SUBMITTED, SELLS_SUBMITTED, WAITING_FUNDS, FAILED, RETRY_LATER
    }

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
    private final Set<String> warned = new HashSet<>();

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
        List<Attempt> attempts = store.attempts(p.id(), mode);
        Optional<Attempt> sellStep = attempts.stream().filter(a -> a.phase() == Phase.SELL).findFirst();
        boolean done = attempts.stream().anyMatch(a -> a.phase() != Phase.SELL)
                || sellStep.filter(a -> !continuable(a.status())).isPresent();
        if (done) {
            return Outcome.ALREADY;
        }
        Instant now = clock.instant();
        ReservationWindow.Decision w = ReservationWindow.check(now, plans.calendar(), p.tradeDate(), p.expiresAt());
        if (!w.open()) {
            log.debug("주문안 #{} 예약 대기: {}", p.id(), w.reason());
            return Outcome.WAITING;
        }
        LocalDate today = now.atZone(ReservationWindow.SEOUL).toLocalDate();
        if (sellStep.isPresent() && !today.isAfter(sellStep.get().day())) {
            return Outcome.WAITING; // 매수는 매도가 나간 장 다음 거래일부터
        }

        OrderPlanner.Result planned = OrderPlanner.plan(p, limits, store.submittedUsdOn(today));
        List<String> problems = new ArrayList<>(planned.problems());
        List<Order> sells = planned.orders().stream().filter(o -> o.side() == Side.SELL).toList();
        List<Order> buys = planned.orders().stream().filter(o -> o.side() == Side.BUY).toList();
        Phase phase = Phase.ALL;
        List<Order> toSend = planned.orders();

        if (sellStep.isPresent()) {
            phase = Phase.BUY;
            toSend = buys;
            if (buys.isEmpty()) {
                return Outcome.ALREADY;
            }
        }
        if (planned.ok() && !buys.isEmpty()) {
            BigDecimal need = planned.total(Side.BUY);
            BigDecimal have;
            try {
                Order b = buys.get(0);
                have = kis.get().buyable(b.exchange(), b.symbol(), b.limitPrice()).orderableUsd();
            } catch (RuntimeException e) {
                return warnOnce("retry:" + p.id(), p, Outcome.RETRY_LATER,
                        DispatchMessages.retryLater(p, "매수 가능 금액 조회 실패: " + e.getMessage()));
            }
            if (phase == Phase.BUY) {
                if (need.compareTo(have) > 0) {
                    return warnOnce("funds:" + p.id(), p, Outcome.WAITING_FUNDS, DispatchMessages.waitingFunds(p, need, have));
                }
            } else if (need.compareTo(have) > 0) {
                BigDecimal fromSells = planned.total(Side.SELL);
                if (!sells.isEmpty() && need.compareTo(have.add(fromSells)) <= 0) {
                    phase = Phase.SELL;
                    toSend = sells;
                } else {
                    problems.add("매수 합계 $" + OrderPlanner.money(need) + "가 매수 가능 금액 $" + OrderPlanner.money(have)
                            + (sells.isEmpty() ? "" : "에 매도 예상 대금 $" + OrderPlanner.money(fromSells) + "를 더한 것") + "보다 큼");
                }
            }
        }

        Optional<Long> started = store.start(p.id(), mode, phase);
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
            toSend.forEach(o -> store.addOrder(dispatch, o, "PLANNED"));
            store.finish(dispatch, "DRY_RUN", null);
            log.info("주문안 #{} 드라이런({}): {}건 기록", p.id(), phase, toSend.size());
            notifier.send(DispatchMessages.dryRun(p, toSend, phase));
            return Outcome.DRY_RUN;
        }
        return send(p, dispatch, toSend, phase, today);
    }

    /** SELL 단계가 이 상태로 끝났으면 다음 거래일에 BUY 단계로 이어 갈 수 있다. */
    private boolean continuable(String status) {
        return mode == Mode.DRY_RUN ? "DRY_RUN".equals(status) : "SUBMITTED".equals(status);
    }

    private record Accepted(Order order, long row, String orderNo) {
    }

    private Outcome send(PlanView p, long dispatch, List<Order> orders, Phase phase, LocalDate today) {
        KisClient client = kis.get();
        List<Accepted> accepted = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            long row = store.addOrder(dispatch, o, "SENDING");
            KisClient.Reply r;
            try {
                r = client.reserve(o.side(), o.exchange(), o.symbol(), o.qty(), o.limitPrice());
            } catch (RuntimeException e) {
                r = failedReply("전송 오류: " + e.getMessage());
            }
            store.orderResult(row, r.ok() ? "SUBMITTED" : "FAILED", r);
            if (!r.ok()) {
                List<Order> cancelled = new ArrayList<>();
                List<Order> stillOpen = new ArrayList<>();
                cancelAccepted(client, accepted, today, cancelled, stillOpen);
                String why = o.symbol() + " " + label(o.side()) + " 접수 실패: " + r.summary();
                store.finish(dispatch, "FAILED", why + " (앞서 접수된 " + accepted.size() + "건 중 취소 " + cancelled.size()
                        + "건, 취소 실패 " + stillOpen.size() + "건. 나머지는 보내지 않음)");
                log.error("주문안 #{} 예약주문 실패: {} / 취소 {}건, 취소 실패 {}건", p.id(), why, cancelled.size(), stillOpen.size());
                notifier.send(DispatchMessages.failed(p, o, r.summary(), cancelled, stillOpen, orders.size() - i - 1));
                return Outcome.FAILED;
            }
            accepted.add(new Accepted(o, row, r.field("odno")));
        }
        store.finish(dispatch, "SUBMITTED", null);
        log.info("주문안 #{} 예약주문 {}건 접수 ({})", p.id(), accepted.size(), phase);
        notifier.send(DispatchMessages.submitted(p, orders, phase));
        return phase == Phase.SELL ? Outcome.SELLS_SUBMITTED : Outcome.SUBMITTED;
    }

    /** 이번 시도에서 접수된 줄을 나중 것부터 예약 취소한다. 접수일자는 오늘(한국) 날짜. */
    private void cancelAccepted(KisClient client, List<Accepted> accepted, LocalDate today,
                                List<Order> cancelled, List<Order> stillOpen) {
        for (int i = accepted.size() - 1; i >= 0; i--) {
            Accepted a = accepted.get(i);
            KisClient.Reply c;
            if (a.orderNo() == null || a.orderNo().isBlank()) {
                c = failedReply("주문번호가 없어 취소할 수 없음");
            } else {
                try {
                    c = client.cancelReservation(a.orderNo(), today.format(YMD));
                } catch (RuntimeException e) {
                    c = failedReply("취소 요청 오류: " + e.getMessage());
                }
            }
            boolean done = KisClient.cancelled(c);
            store.cancelResult(a.row(), done ? "CANCELLED" : "CANCEL_FAILED", c);
            (done ? cancelled : stillOpen).add(a.order());
        }
    }

    private static KisClient.Reply failedReply(String message) {
        return new KisClient.Reply(0, null, null, message, Map.of(), null);
    }

    private Outcome warnOnce(String key, PlanView p, Outcome outcome, String message) {
        log.warn("주문안 #{} 대기 ({}): {}", p.id(), outcome, message);
        if (warned.add(key)) {
            notifier.send(message);
        }
        return outcome;
    }

    static String label(Side s) {
        return s == Side.BUY ? "매수" : "매도";
    }
}

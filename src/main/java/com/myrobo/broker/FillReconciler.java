package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Attempt;
import com.myrobo.broker.DispatchStore.FillRecord;
import com.myrobo.broker.DispatchStore.LiveOrder;
import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.broker.DispatchStore.Phase;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.ops.Notifier;
import com.myrobo.plan.ExecutionInput;
import com.myrobo.plan.PlanViews.ExecutionResult;
import com.myrobo.plan.PlanViews.ExecutionStatus;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 증권사에 접수된 주문이 장에서 어떻게 됐는지 주문체결내역으로 확인해 체결을 반영한다. 앱이 켜져 있는 동안
 * 주기적으로 {@link #tick()}이 불린다.
 *
 * <ul>
 *   <li>언제: 접수한 주문이 나간 장이 모두 끝난 뒤(뉴욕 {@link #FINAL_AFTER_NY}). 매도 먼저 → 다음 거래일 매수로 나뉜
 *       주문안은 매수까지 나간 뒤에. 매수가 끝내 못 나가면 유효기한 마지막 장이 끝난 뒤 매도분만 반영한다</li>
 *   <li>짝 맞추기: 예약주문은 장 시작에 새 주문번호로 나가므로(예약 267 → 주문 36617) 번호 대신
 *       주문일·종목·매수매도·수량·지정가로 찾는다. 없거나 둘 이상이면 멈춘다</li>
 *   <li>반영: 체결분은 체결 평균가로, 남은 수량과 접수되지 않은 줄은 미체결. 체결 입력과 같은 방식으로
 *       주문안을 DONE으로 닫고 새 잔고를 만드는데, 반영 후 보유 수량이 증권사 잔고와 종목마다 같을 때만 저장한다.
 *       증권사 앱에서 손으로 거래했으면 여기서 멈춘다 (사람이 확인). 현금은 대조하지 않는다</li>
 *   <li>수수료: 체결내역에 수수료 칸이 없어 0으로 둔다 (모의는 체결금액 = 수량 × 단가). 실전 전에 출처를 정한다</li>
 *   <li>조회 실패는 기록 없이 다음 틱에 다시. 알림은 같은 이유마다 한 번</li>
 * </ul>
 */
public final class FillReconciler {

    private static final Logger log = LoggerFactory.getLogger(FillReconciler.class);
    /** 정규장 마감(16:00) 뒤 여유를 둔 시각. 이 뒤에는 그날 주문이 더 체결되지 않는다고 본다. */
    static final LocalTime FINAL_AFTER_NY = LocalTime.of(17, 0);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");
    private static final int PRICE_SCALE = 6;

    public enum Outcome {
        OFF, NO_PLAN, NOTHING_SENT, WAITING, RETRY_LATER, STOPPED, SETTLED
    }

    /** 체결 저장: 주문안 DONE·체결·새 잔고와 broker_order 체결 칸을 한 트랜잭션으로. */
    @FunctionalInterface
    public interface Ledger {
        ExecutionResult settle(long planId, ExecutionInput input, Map<Long, OffsetDateTime> executedAt,
                               Map<String, Long> brokerQty, List<FillRecord> records);
    }

    /** 주문 줄과 짝지은 체결내역 한 줄. */
    record Matched(LiveOrder order, KisClient.Fill fill) {
    }

    record Match(List<Matched> matched, List<String> problems) {
    }

    private final Mode mode;
    private final Supplier<KisClient> kis;
    private final DispatchStore store;
    private final OrderDispatcher.PlanSource plans;
    private final Ledger ledger;
    private final Notifier notifier;
    private final Set<String> exchanges;
    private final Clock clock;
    private final Set<String> warned = new HashSet<>();

    /**
     * @param mode      null이거나 드라이런이면 꺼짐 (드라이런은 실제 주문이 없다)
     * @param exchanges 잔고를 조회할 거래소 코드 (설정의 종목별 거래소)
     */
    public FillReconciler(Mode mode, Supplier<KisClient> kis, DispatchStore store, OrderDispatcher.PlanSource plans,
                          Ledger ledger, Notifier notifier, Set<String> exchanges, Clock clock) {
        this.mode = mode;
        this.kis = kis;
        this.store = store;
        this.plans = plans;
        this.ledger = ledger;
        this.notifier = notifier;
        this.exchanges = new TreeSet<>(exchanges);
        this.clock = clock;
    }

    public synchronized Outcome tick() {
        if (mode != Mode.PAPER) {
            return Outcome.OFF;
        }
        Optional<PlanView> open = plans.openPlan();
        if (open.isEmpty() || !"SENT".equals(open.get().status())) {
            return Outcome.NO_PLAN;
        }
        PlanView p = open.get();
        List<LiveOrder> live = store.liveOrders(p.id(), mode);
        if (live.isEmpty()) {
            return Outcome.NOTHING_SENT;
        }
        Instant now = clock.instant();
        LocalDate first = live.stream().map(LiveOrder::orderDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = live.stream().map(LiveOrder::orderDate).max(Comparator.naturalOrder()).orElseThrow();
        if (now.isBefore(sessionOver(last)) || buyStillToCome(p, now)) {
            return Outcome.WAITING;
        }

        List<KisClient.Fill> fills;
        Map<String, Long> held;
        try {
            KisClient c = kis.get();
            fills = c.fills(first, now.atZone(SEOUL).toLocalDate());
            held = brokerQty(c, live);
        } catch (RuntimeException e) {
            return warnOnce("retry:" + p.id(), p, Outcome.RETRY_LATER, FillMessages.retryLater(p, e.getMessage()));
        }

        Match m = match(live, fills);
        if (!m.problems().isEmpty()) {
            return stop(p, m.problems());
        }
        Map<Long, Matched> byItem = new HashMap<>();
        m.matched().forEach(x -> byItem.put(x.order().itemId(), x));
        List<ExecutionInput.Fill> input = new ArrayList<>();
        Map<Long, OffsetDateTime> at = new HashMap<>();
        List<ItemView> notSent = new ArrayList<>();
        for (ItemView item : p.items()) {
            Matched x = byItem.get(item.id());
            if (x == null) {
                notSent.add(item);
            }
            if (x == null || x.fill().filledQty() == 0) {
                input.add(new ExecutionInput.Fill(item.id(), 0L, null, null));
                continue;
            }
            input.add(new ExecutionInput.Fill(item.id(), x.fill().filledQty(), price(x.fill().filledPrice()), BigDecimal.ZERO));
            OffsetDateTime t = orderTime(x.fill());
            if (t != null) {
                at.put(item.id(), t);
            }
        }
        List<FillRecord> records = m.matched().stream().map(FillReconciler::record).toList();

        ExecutionResult r = ledger.settle(p.id(), new ExecutionInput(input, null), at, held, records);
        if (r.status() != ExecutionStatus.SAVED) {
            return stop(p, r.errors().isEmpty() ? List.of(r.message()) : r.errors());
        }
        log.info("주문안 #{} 체결 반영: {}줄 짝 맞춤, 접수 안 된 줄 {}개", p.id(), m.matched().size(), notSent.size());
        notifier.send(FillMessages.settled(p, m.matched(), notSent));
        return Outcome.SETTLED;
    }

    /**
     * 주문 줄마다 체결내역에서 같은 주문을 하나 찾는다. 정정·취소 줄(원주문번호가 있음)은 보지 않는다.
     * 순수 계산이라 테스트에서 실제 응답으로 확인한다.
     */
    static Match match(List<LiveOrder> orders, List<KisClient.Fill> fills) {
        List<Matched> matched = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<Long> items = new HashSet<>();
        for (LiveOrder o : orders) {
            String day = o.orderDate().format(YMD);
            List<KisClient.Fill> c = fills.stream()
                    .filter(f -> isOriginal(f) && f.side() == o.side() && o.symbol().equals(f.symbol())
                            && f.orderQty() == o.qty() && f.orderPrice().compareTo(o.limitPrice()) == 0
                            && day.equals(f.orderDate()))
                    .toList();
            String label = label(o);
            if (c.isEmpty()) {
                problems.add(label + ": 주문체결내역에 없음 (장에 나가지 않았거나 거부됐을 수 있음)");
                continue;
            }
            if (c.size() > 1) {
                problems.add(label + ": 주문체결내역에 같은 주문이 " + c.size() + "건");
                continue;
            }
            KisClient.Fill f = c.get(0);
            if (!used.add(f.orderDate() + "/" + f.orderNo())) {
                problems.add(label + ": 다른 줄과 같은 체결내역(주문번호 " + f.orderNo() + ")에 짝지어짐");
                continue;
            }
            if (!items.add(o.itemId())) {
                problems.add(label + ": 같은 주문안 줄에 접수된 주문이 둘 이상");
                continue;
            }
            if (f.filledQty() < 0 || f.filledQty() > o.qty()
                    || (f.filledQty() > 0 && f.filledPrice().signum() <= 0)) {
                problems.add(label + ": 체결 수량·가격이 이상함 (" + f.filledQty() + "주 @ " + f.filledPrice() + ")");
                continue;
            }
            matched.add(new Matched(o, f));
        }
        return new Match(List.copyOf(matched), List.copyOf(problems));
    }

    /** 이 뉴욕 거래일 장의 주문이 더 체결되지 않는다고 보는 시각. */
    static Instant sessionOver(LocalDate nyDate) {
        return nyDate.atTime(FINAL_AFTER_NY).atZone(MarketCalendar.NEW_YORK).toInstant();
    }

    /** 매도만 먼저 접수했고 매수는 아직 시도 전인데, 유효기한 마지막 장이 끝나기 전이면 매수를 기다린다. */
    private boolean buyStillToCome(PlanView p, Instant now) {
        List<Attempt> attempts = store.attempts(p.id(), mode);
        boolean sellsOnly = attempts.stream().anyMatch(a -> a.phase() == Phase.SELL && "SUBMITTED".equals(a.status()))
                && attempts.stream().noneMatch(a -> a.phase() == Phase.BUY);
        boolean hasBuys = p.items().stream().anyMatch(i -> "BUY".equals(i.side()));
        return sellsOnly && hasBuys && p.expiresAt() != null && now.isBefore(sessionOver(p.expiresAt()));
    }

    /** 증권사 잔고 종목 → 수량. 거래소 코드마다 조회해 합친다 (모의 서버는 다른 코드로도 같은 종목을 돌려줬다). */
    private Map<String, Long> brokerQty(KisClient c, List<LiveOrder> live) {
        Set<String> codes = new TreeSet<>(exchanges);
        live.forEach(o -> codes.add(o.exchange()));
        Map<String, Long> qty = new TreeMap<>();
        for (String code : codes) {
            for (KisClient.Position pos : c.positions(code)) {
                Long before = qty.putIfAbsent(pos.symbol(), pos.qty());
                if (before != null && before != pos.qty()) {
                    throw new KisException(pos.symbol() + " 잔고가 거래소 코드마다 다르게 조회됨 (" + before + "주, "
                            + pos.qty() + "주)");
                }
            }
        }
        return qty;
    }

    private static FillRecord record(Matched x) {
        KisClient.Fill f = x.fill();
        long q = f.filledQty();
        String status = q == 0 ? "UNFILLED" : q == x.order().qty() ? "FILLED" : "PARTIAL";
        return new FillRecord(x.order().id(), status, f.orderNo(), q, q == 0 ? null : price(f.filledPrice()),
                q == 0 ? null : orderTime(f));
    }

    private static boolean isOriginal(KisClient.Fill f) {
        String o = f.originalOrderNo() == null ? "" : f.originalOrderNo().strip();
        return o.isEmpty() || o.chars().allMatch(ch -> ch == '0');
    }

    /** 응답의 단가("373.43500000")를 체결 입력 자리수(소수 6자리)에 맞춘다. */
    static BigDecimal price(BigDecimal p) {
        BigDecimal s = p.stripTrailingZeros();
        if (s.scale() < 0) {
            return s.setScale(0);
        }
        return s.scale() > PRICE_SCALE ? s.setScale(PRICE_SCALE, RoundingMode.HALF_UP) : s;
    }

    /** 체결내역에는 체결 시각이 없어 주문 시각(한국 시간)을 쓴다. 읽을 수 없으면 null (저장 시각으로). */
    static OffsetDateTime orderTime(KisClient.Fill f) {
        try {
            return LocalDate.parse(f.orderDate(), YMD).atTime(LocalTime.parse(f.orderTime(), HMS)).atZone(SEOUL)
                    .toOffsetDateTime();
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private Outcome stop(PlanView p, List<String> problems) {
        return warnOnce("stop:" + p.id() + ":" + String.join("|", problems), p, Outcome.STOPPED,
                FillMessages.stopped(p, problems));
    }

    private Outcome warnOnce(String key, PlanView p, Outcome outcome, String message) {
        log.warn("주문안 #{} 체결 반영 ({}): {}", p.id(), outcome, message);
        if (warned.add(key)) {
            notifier.send(message);
        }
        return outcome;
    }

    static String label(LiveOrder o) {
        return OrderDispatcher.label(o.side()) + " " + o.symbol() + " " + o.qty() + "주 지정가 $"
                + OrderPlanner.money(o.limitPrice()) + " (" + o.orderDate() + ")";
    }
}

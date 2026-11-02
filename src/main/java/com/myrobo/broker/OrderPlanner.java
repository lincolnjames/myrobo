package com.myrobo.broker;

import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 발송(SENT)된 주문안 → 증권사에 보낼 지정가 주문 목록. 순수 계산.
 *
 * <p>지정가는 주문안의 예상가(직전 종가)에 여유를 둔다: 매수는 위로 올림, 매도는 아래로 내림 (센트 단위).
 * 장이 그만큼 넘게 움직이면 체결되지 않고 그날 자동 취소된다 (더 나쁜 값에 사지 않는다).
 *
 * <p>하나라도 문제가 있으면 전체를 보내지 않는다 (일부만 실행하는 경로를 만들지 않는다).
 * 순서는 매도 먼저, 그다음 매수.
 */
public final class OrderPlanner {

    public enum Side { BUY, SELL }

    /**
     * @param bufferPct   지정가 여유 (%). 매수는 예상가 × (1 + 이것), 매도는 × (1 - 이것)
     * @param maxOrderUsd 주문 한 줄의 최대 금액 (수량 × 지정가)
     * @param maxDailyUsd 하루에 보낼 수 있는 주문 합계 (이미 보낸 것 포함)
     * @param exchanges   종목 → 주문용 거래소 코드 (NASD, NYSE, AMEX). 없는 종목은 보내지 않는다
     */
    public record Limits(BigDecimal bufferPct, BigDecimal maxOrderUsd, BigDecimal maxDailyUsd,
                         Map<String, String> exchanges) {
    }

    public record Order(long itemId, Side side, String symbol, String exchange, long qty, BigDecimal limitPrice) {

        public BigDecimal amount() {
            return limitPrice.multiply(BigDecimal.valueOf(qty));
        }
    }

    /** problems가 비어 있어야 보낼 수 있다. */
    public record Result(List<Order> orders, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }

        public BigDecimal total(Side side) {
            return orders.stream().filter(o -> o.side() == side).map(Order::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OrderPlanner() {
    }

    /** @param alreadySentTodayUsd 오늘 이미 증권사에 보낸 주문 합계 (하루 한도 계산용) */
    public static Result plan(PlanView p, Limits l, BigDecimal alreadySentTodayUsd) {
        List<String> problems = new ArrayList<>();
        if (!"SENT".equals(p.status())) {
            problems.add("주문안 #" + p.id() + " 상태가 SENT가 아님 (" + p.status() + ")");
        }
        if (p.fxUsd() != null) {
            problems.add("환전이 필요한 주문안은 아직 자동으로 보내지 않아요 (원화 → $" + p.fxUsd() + ")");
        }
        if (p.items().isEmpty()) {
            problems.add("주문 줄이 없음");
        }
        if (l.bufferPct().signum() < 0 || l.bufferPct().compareTo(BigDecimal.TEN) > 0) {
            problems.add("지정가 여유가 0~10% 밖 (" + l.bufferPct().toPlainString() + "%)");
        }

        BigDecimal up = BigDecimal.ONE.add(l.bufferPct().divide(HUNDRED));
        BigDecimal down = BigDecimal.ONE.subtract(l.bufferPct().divide(HUNDRED));
        List<Order> sells = new ArrayList<>();
        List<Order> buys = new ArrayList<>();
        for (ItemView it : p.items()) {
            String exchange = l.exchanges().get(it.symbol());
            if (exchange == null) {
                problems.add(it.symbol() + ": 주문용 거래소 코드가 설정에 없음 (robo.broker.exchanges)");
                continue;
            }
            if (it.qty() <= 0 || it.estPrice() == null || it.estPrice().signum() <= 0) {
                problems.add(it.symbol() + ": 수량이나 예상가가 이상함");
                continue;
            }
            Side side = Side.valueOf(it.side());
            BigDecimal limit = side == Side.BUY
                    ? it.estPrice().multiply(up).setScale(2, RoundingMode.CEILING)
                    : it.estPrice().multiply(down).setScale(2, RoundingMode.FLOOR);
            if (limit.signum() <= 0) {
                problems.add(it.symbol() + ": 지정가가 0 이하");
                continue;
            }
            Order o = new Order(it.id(), side, it.symbol(), exchange, it.qty(), limit);
            if (o.amount().compareTo(l.maxOrderUsd()) > 0) {
                problems.add(it.symbol() + " " + (side == Side.BUY ? "매수" : "매도") + " $" + money(o.amount())
                        + "가 주문 한 건 한도 $" + money(l.maxOrderUsd()) + "를 넘음");
            }
            (side == Side.SELL ? sells : buys).add(o);
        }
        List<Order> orders = new ArrayList<>(sells);
        orders.addAll(buys);
        BigDecimal total = orders.stream().map(Order::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal today = alreadySentTodayUsd.add(total);
        if (today.compareTo(l.maxDailyUsd()) > 0) {
            problems.add("오늘 주문 합계 $" + money(today) + "가 하루 한도 $" + money(l.maxDailyUsd()) + "를 넘음"
                    + (alreadySentTodayUsd.signum() > 0 ? " (이미 보낸 $" + money(alreadySentTodayUsd) + " 포함)" : ""));
        }
        return new Result(List.copyOf(orders), List.copyOf(problems));
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

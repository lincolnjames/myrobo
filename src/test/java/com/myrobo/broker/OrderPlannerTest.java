package com.myrobo.broker;

import com.myrobo.broker.OrderPlanner.Order;
import com.myrobo.broker.OrderPlanner.Side;
import com.myrobo.plan.PlanViews.ItemView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.myrobo.broker.BrokerFixtures.d;
import static com.myrobo.broker.BrokerFixtures.limits;
import static com.myrobo.broker.BrokerFixtures.plan;
import static com.myrobo.broker.BrokerFixtures.plan4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 주문안 → 지정가 주문: 순서, 지정가 반올림 방향, 한도, 하나라도 문제면 전체 차단. */
class OrderPlannerTest {

    @Test
    void sellsFirstThenBuysWithBufferedLimitPrices() {
        OrderPlanner.Result r = OrderPlanner.plan(plan4(), limits(), BigDecimal.ZERO);

        assertTrue(r.ok(), r.problems().toString());
        assertEquals(List.of("SELL BIL 12 90.78", "BUY SCHR 19 24.40", "BUY VTI 1 375.29", "BUY GLDM 2 77.60"),
                r.orders().stream().map(o -> o.side() + " " + o.symbol() + " " + o.qty() + " " + o.limitPrice()).toList());
        assertTrue(r.orders().stream().allMatch(o -> o.exchange().equals("AMEX")));
    }

    @Test
    void buyLimitRoundsUpAndSellLimitRoundsDownToTheCent() {
        // 91.70 × 0.99 = 90.783 → 90.78 (매도는 내림), 24.15 × 1.01 = 24.3915 → 24.40 (매수는 올림)
        OrderPlanner.Result r = OrderPlanner.plan(plan4(), limits(), BigDecimal.ZERO);

        assertEquals(d("90.78"), r.orders().get(0).limitPrice());
        assertEquals(d("24.40"), r.orders().get(1).limitPrice());
    }

    @Test
    void totalsBySide() {
        OrderPlanner.Result r = OrderPlanner.plan(plan4(), limits(), BigDecimal.ZERO);

        assertEquals(0, d("1089.36").compareTo(r.total(Side.SELL)));          // 12 × 90.78
        assertEquals(0, d("994.09").compareTo(r.total(Side.BUY)));            // 19×24.40 + 375.29 + 2×77.60
    }

    @Test
    void missingExchangeBlocksTheWholePlan() {
        var l = new OrderPlanner.Limits(d("1.0"), d("15000"), d("40000"), Map.of("VTI", "AMEX", "SCHR", "AMEX", "GLDM", "AMEX"));

        OrderPlanner.Result r = OrderPlanner.plan(plan4(), l, BigDecimal.ZERO);

        assertFalse(r.ok());
        assertTrue(r.problems().get(0).startsWith("BIL: 주문용 거래소 코드"), r.problems().toString());
    }

    @Test
    void planThatNeedsCurrencyExchangeIsNotSent() {
        var p = plan(5, "SENT", 1000L, List.of(new ItemView(1, "BUY", "VTI", 1, d("371.57"), d("371.57"))));

        assertTrue(OrderPlanner.plan(p, limits(), BigDecimal.ZERO).problems().get(0).startsWith("환전이 필요한"));
    }

    @Test
    void onlySentPlans() {
        var p = plan(6, "REJECTED", null, List.of(new ItemView(1, "BUY", "VTI", 1, d("371.57"), d("371.57"))));

        assertFalse(OrderPlanner.plan(p, limits(), BigDecimal.ZERO).ok());
    }

    @Test
    void lineOverTheSingleOrderLimitBlocksEverything() {
        var p = plan(7, "SENT", null, List.of(
                new ItemView(1, "BUY", "VTI", 50, d("371.57"), d("18578.50")),
                new ItemView(2, "BUY", "GLDM", 1, d("76.83"), d("76.83"))));

        OrderPlanner.Result r = OrderPlanner.plan(p, limits(), BigDecimal.ZERO);

        assertFalse(r.ok());
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).contains("주문 한 건 한도 $15000.00"), r.problems().get(0));
    }

    @Test
    void dailyLimitCountsWhatWasAlreadySentToday() {
        OrderPlanner.Result r = OrderPlanner.plan(plan4(), limits(), d("39000"));

        assertFalse(r.ok());
        assertTrue(r.problems().get(0).contains("하루 한도 $40000.00") && r.problems().get(0).contains("이미 보낸 $39000.00"),
                r.problems().get(0));
    }

    @Test
    void zeroBufferUsesTheEstimatedPrice() {
        var l = new OrderPlanner.Limits(BigDecimal.ZERO, d("15000"), d("40000"), limits().exchanges());

        List<Order> orders = OrderPlanner.plan(plan4(), l, BigDecimal.ZERO).orders();

        assertEquals(d("91.70"), orders.get(0).limitPrice());
        assertEquals(d("371.57"), orders.get(2).limitPrice());
    }

    @Test
    void absurdBufferIsRejected() {
        var l = new OrderPlanner.Limits(d("50"), d("15000"), d("40000"), limits().exchanges());

        assertFalse(OrderPlanner.plan(plan4(), l, BigDecimal.ZERO).ok());
    }
}

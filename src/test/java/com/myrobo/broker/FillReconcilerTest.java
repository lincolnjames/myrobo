package com.myrobo.broker;

import com.myrobo.broker.BrokerFixtures.FakeKis;
import com.myrobo.broker.BrokerFixtures.MemoryStore;
import com.myrobo.broker.BrokerFixtures.RecordingNotifier;
import com.myrobo.broker.BrokerFixtures.TestClock;
import com.myrobo.broker.DispatchStore.FillRecord;
import com.myrobo.broker.DispatchStore.LiveOrder;
import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.broker.DispatchStore.Phase;
import com.myrobo.broker.FillReconciler.Outcome;
import com.myrobo.broker.OrderPlanner.Side;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.plan.ExecutionInput;
import com.myrobo.plan.PlanViews.ExecutionResult;
import com.myrobo.plan.PlanViews.ExecutionStatus;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.myrobo.broker.BrokerFixtures.BALANCE_AMEX;
import static com.myrobo.broker.BrokerFixtures.CCNL_1102;
import static com.myrobo.broker.BrokerFixtures.NO_ROWS;
import static com.myrobo.broker.BrokerFixtures.TOKEN_OK;
import static com.myrobo.broker.BrokerFixtures.client;
import static com.myrobo.broker.BrokerFixtures.d;
import static com.myrobo.broker.BrokerFixtures.plan;
import static com.myrobo.broker.BrokerFixtures.plan1;
import static com.myrobo.broker.BrokerFixtures.plan1Live;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 체결 반영: 장이 끝난 뒤에만, 주문일·종목·수량·지정가로 짝 맞추기, 증권사 잔고와 수량이 같을 때만 저장, 아니면 멈춤. */
class FillReconcilerTest {

    private static final String FILLS = KisClient.FILLS_PATH;
    private static final String BALANCE = KisClient.BALANCE_PATH;
    /** 11-03 10:00 KST: 11-02 장(뉴욕)이 끝난 뒤. */
    private static final String MORNING_AFTER = "2026-11-03T01:00:00Z";

    private static final class Fixture {
        final FakeKis kis = new FakeKis().on(KisClient.TOKEN_PATH, TOKEN_OK).on(FILLS, CCNL_1102).on(BALANCE, BALANCE_AMEX);
        final TestClock clock = new TestClock(MORNING_AFTER);
        final MemoryStore store = new MemoryStore(clock);
        final RecordingNotifier notifier = new RecordingNotifier();
        final List<Settle> settled = new ArrayList<>();
        PlanView open = plan1();
        ExecutionResult answer = new ExecutionResult(ExecutionStatus.SAVED, "저장", List.of(), null, null);

        record Settle(long planId, ExecutionInput input, Map<Long, OffsetDateTime> at, Map<String, Long> qty,
                      List<FillRecord> records) {
        }

        Fixture() {
            store.live.addAll(plan1Live());
            store.attempt(Phase.ALL, "SUBMITTED", LocalDate.of(2026, 11, 2));
        }

        FillReconciler reconciler(Mode mode) {
            KisClient c = client(kis, clock);
            OrderDispatcher.PlanSource src = new OrderDispatcher.PlanSource() {
                @Override
                public Optional<PlanView> openPlan() {
                    return Optional.ofNullable(open);
                }

                @Override
                public MarketCalendar calendar() {
                    return new MarketCalendar(Set.of());
                }
            };
            FillReconciler.Ledger ledger = (planId, input, at, qty, records) -> {
                settled.add(new Settle(planId, input, at, qty, records));
                return answer;
            };
            return new FillReconciler(mode, () -> c, store, src, ledger, notifier, Set.of("AMEX"), clock);
        }
    }

    @Test
    void offAndDryRunDoNothing() {
        var f = new Fixture();

        assertEquals(Outcome.OFF, f.reconciler(null).tick());
        assertEquals(Outcome.OFF, f.reconciler(Mode.DRY_RUN).tick());

        assertTrue(f.kis.sent.isEmpty() && f.settled.isEmpty());
    }

    @Test
    void nothingToDoWithoutSubmittedOrders() {
        var f = new Fixture();
        f.store.live.clear(); // 차단·실패 후 전부 취소됐으면 사람이 화면에서 처리

        assertEquals(Outcome.NOTHING_SENT, f.reconciler(Mode.PAPER).tick());
        assertTrue(f.kis.sent.isEmpty());
    }

    @Test
    void waitsQuietlyUntilTheSessionIsOver() {
        var f = new Fixture();
        f.clock.now = Instant.parse("2026-11-02T21:59:00Z"); // 뉴욕 16:59 (11-02은 서머타임이 끝난 뒤)

        assertEquals(Outcome.WAITING, f.reconciler(Mode.PAPER).tick());

        assertTrue(f.kis.sent.isEmpty() && f.notifier.messages.isEmpty());
        f.clock.now = Instant.parse("2026-11-02T22:00:00Z");
        assertEquals(Outcome.SETTLED, f.reconciler(Mode.PAPER).tick());
    }

    @Test
    void settlesTheRealPaperFillsAndTreatsTheUnsentLineAsUnfilled() {
        var f = new Fixture();

        assertEquals(Outcome.SETTLED, f.reconciler(Mode.PAPER).tick());

        assertEquals(1, f.settled.size());
        var s = f.settled.get(0);
        assertEquals(1, s.planId());
        List<ExecutionInput.Fill> fills = s.input().fills();
        assertEquals(List.of(1L, 2L, 3L, 4L), fills.stream().map(ExecutionInput.Fill::itemId).toList());
        assertEquals(List.of(26L, 345L, 45L, 0L), fills.stream().map(ExecutionInput.Fill::qty).toList());
        assertEquals("373.435", fills.get(0).price().toPlainString());
        assertEquals("24.06", fills.get(1).price().toPlainString());
        assertEquals("91.41", fills.get(2).price().toPlainString());
        assertNull(fills.get(3).price());
        assertEquals(0, d("0").compareTo(fills.get(0).fee())); // 체결내역에 수수료 칸이 없다
        assertNull(s.input().fx());
        assertEquals(OffsetDateTime.parse("2026-11-02T23:30:03+09:00"), s.at().get(1L)); // 주문 시각 (한국)
        assertEquals(Map.of("BIL", 45L, "SCHR", 345L, "VTI", 26L), s.qty());

        assertEquals(List.of("FILLED", "FILLED", "FILLED"), s.records().stream().map(FillRecord::fillStatus).toList());
        assertEquals(List.of("36617", "36618", "36619"), s.records().stream().map(FillRecord::fillOrderNo).toList());
        assertEquals(List.of(1L, 2L, 3L), s.records().stream().map(FillRecord::orderId).toList());

        String q = f.kis.to(FILLS).get(0).uri().getRawQuery();
        assertTrue(q.contains("PDNO=&ORD_STRT_DT=20261102&ORD_END_DT=20261103&SLL_BUY_DVSN=00&CCLD_NCCS_DVSN=00&OVRS_EXCG_CD=&"), q);
        assertEquals("VTTS3035R", f.kis.to(FILLS).get(0).headers().get("tr_id"));
        assertEquals("VTTS3012R", f.kis.to(BALANCE).get(0).headers().get("tr_id"));
        assertTrue(f.kis.to(BALANCE).get(0).uri().getRawQuery().contains("OVRS_EXCG_CD=AMEX&TR_CRCY_CD=USD"));

        String msg = f.notifier.messages.get(0);
        assertTrue(msg.contains("- 매수 VTI 26/26주 체결, 평균 $373.435") && msg.contains("GLDM 36주는 접수되지 않아 미체결"), msg);
        assertTrue(!msg.contains("50123456"), msg);
    }

    @Test
    void aMissingOrderStopsAndNotifiesOnce() {
        var f = new Fixture();
        f.kis.scripts.remove(FILLS);
        f.kis.on(FILLS, NO_ROWS);
        FillReconciler r = f.reconciler(Mode.PAPER);

        assertEquals(Outcome.STOPPED, r.tick());
        assertEquals(Outcome.STOPPED, r.tick());

        assertTrue(f.settled.isEmpty());
        assertEquals(1, f.notifier.messages.size());
        String msg = f.notifier.messages.get(0);
        assertTrue(msg.contains("매수 VTI 26주 지정가 $375.29 (2026-11-02): 주문체결내역에 없음") && msg.contains("직접 입력"), msg);
    }

    @Test
    void aQuantityMismatchFromTheLedgerStopsWithTheReason() {
        var f = new Fixture();
        f.answer = new ExecutionResult(ExecutionStatus.CONFLICT, "수량이 달라 저장하지 않았어요.",
                List.of("VTI: 체결 반영 후 26주인데 증권사 잔고는 27주"), null, null);

        assertEquals(Outcome.STOPPED, f.reconciler(Mode.PAPER).tick());

        assertTrue(f.notifier.messages.get(0).contains("VTI: 체결 반영 후 26주인데 증권사 잔고는 27주"));
    }

    @Test
    void aFailedQueryIsRetriedNextTickWithOneNotice() {
        var f = new Fixture();
        f.kis.scripts.remove(FILLS);
        f.kis.on(FILLS, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}");
        FillReconciler r = f.reconciler(Mode.PAPER);

        assertEquals(Outcome.RETRY_LATER, r.tick());
        assertEquals(Outcome.RETRY_LATER, r.tick());

        assertTrue(f.settled.isEmpty());
        assertEquals(1, f.notifier.messages.size());
        assertTrue(f.notifier.messages.get(0).contains("EGW00123"), f.notifier.messages.get(0));
    }

    @Test
    void sellsFirstWaitsForTheBuyStepUntilTheLastSessionOfThePlan() {
        var f = new Fixture();
        f.store.dispatches.clear();
        f.store.attempt(Phase.SELL, "SUBMITTED", LocalDate.of(2026, 11, 2));
        f.open = plan(1, "SENT", null, List.of(new ItemView(1, "SELL", "BIL", 45, d("91.70"), d("4126.50")),
                new ItemView(2, "BUY", "VTI", 26, d("371.57"), d("9660.82"))));
        f.store.live.clear();
        f.store.live.add(new LiveOrder(1, 1, Side.SELL, "BIL", "AMEX", 45, d("90.78"), LocalDate.of(2026, 11, 2)));

        assertEquals(Outcome.WAITING, f.reconciler(Mode.PAPER).tick()); // 매수는 다음 거래일에 나간다

        f.clock.now = FillReconciler.sessionOver(LocalDate.of(2026, 11, 4)); // 유효기한 마지막 장이 끝남
        f.kis.scripts.remove(KisClient.TOKEN_PATH);
        f.kis.on(KisClient.TOKEN_PATH, TOKEN_OK.replace("2026-11-03 14:26:29", "2026-11-06 14:26:29"));
        f.kis.scripts.remove(FILLS);
        f.kis.on(FILLS, NO_ROWS);
        assertEquals(Outcome.STOPPED, f.reconciler(Mode.PAPER).tick()); // 이제는 확인한다 (여기선 매도가 내역에 없음)
    }

    // ── 짝 맞추기 ──

    private static KisClient.Fill fill(String no, String orig, Side side, String symbol, long qty, String limit,
                                       long filled, String price) {
        return new KisClient.Fill("20261102", "233003", no, orig, side, symbol, qty, d(limit), filled, d(price));
    }

    @Test
    void matchesByDateSymbolSideQtyAndLimitPrice() {
        var orders = plan1Live();
        var fills = List.of(
                fill("36617", "0", Side.BUY, "VTI", 26, "375.29000000", 10, "373.4"),     // 일부 체결
                fill("36618", "0", Side.BUY, "SCHR", 345, "24.41000000", 345, "24.06"),   // 지정가가 다름
                fill("36619", "0", Side.BUY, "BIL", 45, "92.62000000", 0, "0"),           // 미체결
                fill("36620", "36619", Side.BUY, "BIL", 45, "92.62000000", 0, "0"));      // 취소 줄은 보지 않음

        var m = FillReconciler.match(orders, fills);

        assertEquals(List.of("매수 SCHR 345주 지정가 $24.40 (2026-11-02): 주문체결내역에 없음 (장에 나가지 않았거나 거부됐을 수 있음)"),
                m.problems());
        assertEquals(List.of("36617", "36619"), m.matched().stream().map(x -> x.fill().orderNo()).toList());
    }

    @Test
    void twoCandidatesForOneOrderAreAProblem() {
        var orders = List.of(plan1Live().get(0));
        var fills = List.of(fill("1", "0", Side.BUY, "VTI", 26, "375.29", 26, "373"),
                fill("2", "0", Side.BUY, "VTI", 26, "375.29", 26, "373"));

        var m = FillReconciler.match(orders, fills);

        assertTrue(m.matched().isEmpty());
        assertTrue(m.problems().get(0).contains("같은 주문이 2건"), m.problems().toString());
    }

    @Test
    void anotherDayOrSideDoesNotMatch() {
        var orders = List.of(plan1Live().get(0));
        var otherDay = new KisClient.Fill("20261103", "233003", "1", "0", Side.BUY, "VTI", 26, d("375.29"), 26, d("373"));
        var sell = fill("2", "0", Side.SELL, "VTI", 26, "375.29", 26, "373");

        assertEquals(1, FillReconciler.match(orders, List.of(otherDay, sell)).problems().size());
    }

    @Test
    void partialFillIsRecordedAsPartial() {
        var f = new Fixture();
        f.kis.scripts.remove(FILLS);
        f.kis.on(FILLS, CCNL_1102.replace("\"ft_ccld_qty\":\"26\"", "\"ft_ccld_qty\":\"10\""));

        f.reconciler(Mode.PAPER).tick();

        var s = f.settled.get(0);
        assertEquals(List.of("PARTIAL", "FILLED", "FILLED"), s.records().stream().map(FillRecord::fillStatus).toList());
        assertEquals(10L, s.input().fills().get(0).qty());
    }

    @Test
    void pricesAreTrimmedToSixDecimals() {
        assertEquals("373.435", FillReconciler.price(d("373.43500000")).toPlainString());
        assertEquals("100", FillReconciler.price(d("100.00000000")).toPlainString());
        assertEquals("1.123457", FillReconciler.price(d("1.1234567")).toPlainString());
    }
}

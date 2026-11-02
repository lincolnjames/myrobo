package com.myrobo.broker;

import com.myrobo.broker.BrokerFixtures.FakeKis;
import com.myrobo.broker.BrokerFixtures.MemoryStore;
import com.myrobo.broker.BrokerFixtures.RecordingNotifier;
import com.myrobo.broker.BrokerFixtures.TestClock;
import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.broker.DispatchStore.Phase;
import com.myrobo.broker.OrderDispatcher.Outcome;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.myrobo.broker.BrokerFixtures.BUYABLE_100K;
import static com.myrobo.broker.BrokerFixtures.CANCEL_FAIL;
import static com.myrobo.broker.BrokerFixtures.CANCEL_OK;
import static com.myrobo.broker.BrokerFixtures.RESERVE_FAIL;
import static com.myrobo.broker.BrokerFixtures.TOKEN_OK;
import static com.myrobo.broker.BrokerFixtures.buyable;
import static com.myrobo.broker.BrokerFixtures.client;
import static com.myrobo.broker.BrokerFixtures.d;
import static com.myrobo.broker.BrokerFixtures.limits;
import static com.myrobo.broker.BrokerFixtures.plan;
import static com.myrobo.broker.BrokerFixtures.plan4;
import static com.myrobo.broker.BrokerFixtures.reserveOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 주문안 → 예약주문 전송: 한 번만, 매도 먼저, 현금이 모자라면 매도 → 다음 거래일 매수, 실패하면 취소하고 멈춤. */
class OrderDispatcherTest {

    private static final String RESERVE = KisClient.RESERVE_PATH;
    private static final String CANCEL = KisClient.CANCEL_PATH;
    private static final String BUYABLE = KisClient.BUYABLE_PATH;
    private static final String NOON_KST = "2026-11-02T03:00:00Z";

    private static final class Fixture {
        final FakeKis kis = new FakeKis().on(KisClient.TOKEN_PATH, TOKEN_OK);
        final TestClock clock = new TestClock(NOON_KST);
        final MemoryStore store = new MemoryStore(clock);
        final RecordingNotifier notifier = new RecordingNotifier();
        PlanView open = plan4();
        OrderDispatcher dispatcher;

        Fixture buyable(String... usd) {
            kis.scripts.remove(BUYABLE);
            for (String u : usd) {
                kis.on(BUYABLE, u == null ? BUYABLE_100K : BrokerFixtures.buyable(u));
            }
            return this;
        }

        OrderDispatcher dispatcher(Mode mode) {
            if (!kis.scripts.containsKey(BUYABLE)) {
                kis.on(BUYABLE, BUYABLE_100K);
            }
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
            dispatcher = new OrderDispatcher(mode, () -> c, store, src, notifier, limits(), clock);
            return dispatcher;
        }

        void nextDay() {
            clock.now = clock.now.plus(Duration.ofDays(1));
        }

        List<String> sent(String path, String header) {
            return kis.to(path).stream().map(s -> s.headers().get(header)).toList();
        }
    }

    @Test
    void offDoesNothing() {
        var f = new Fixture();

        assertEquals(Outcome.OFF, f.dispatcher(null).tick());

        assertTrue(f.kis.sent.isEmpty() && f.store.dispatches.isEmpty() && f.notifier.messages.isEmpty());
    }

    @Test
    void noOpenPlan() {
        var f = new Fixture();
        f.open = null;

        assertEquals(Outcome.NO_PLAN, f.dispatcher(Mode.PAPER).tick());
    }

    @Test
    void waitsQuietlyOutsideTheReservationWindow() {
        var f = new Fixture();
        f.clock.now = Instant.parse("2026-11-01T23:30:00Z"); // KST 08:30

        assertEquals(Outcome.WAITING, f.dispatcher(Mode.PAPER).tick());

        assertTrue(f.kis.sent.isEmpty() && f.store.dispatches.isEmpty() && f.notifier.messages.isEmpty());
    }

    @Test
    void dryRunRecordsAndNotifiesButSendsNoOrder() {
        var f = new Fixture();
        OrderDispatcher d = f.dispatcher(Mode.DRY_RUN);

        assertEquals(Outcome.DRY_RUN, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertTrue(f.kis.to(RESERVE).isEmpty());
        assertEquals(1, f.kis.to(BUYABLE).size()); // 조회는 해 본다
        assertEquals("DRY_RUN", f.store.dispatch(Phase.ALL).status());
        assertEquals(List.of("PLANNED", "PLANNED", "PLANNED", "PLANNED"), f.store.orders.stream().map(o -> o.status()).toList());
        assertEquals(1, f.notifier.messages.size());
        assertTrue(f.notifier.messages.get(0).contains("드라이런") && f.notifier.messages.get(0).contains("- 매도 BIL 12주 지정가 $90.78 (AMEX)"),
                f.notifier.messages.get(0));
    }

    @Test
    void enoughCashSendsEverythingAtOnceSellsFirst() {
        var f = new Fixture();
        f.kis.on(RESERVE, reserveOk("001"), reserveOk("002"), reserveOk("003"), reserveOk("004"));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.SUBMITTED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertEquals(List.of("VTTT3016U", "VTTT3014U", "VTTT3014U", "VTTT3014U"), f.sent(RESERVE, "tr_id"));
        assertEquals(List.of("BIL", "SCHR", "VTI", "GLDM"), f.store.orders.stream().map(o -> o.order().symbol()).toList());
        assertTrue(f.store.orders.stream().allMatch(o -> o.status().equals("SUBMITTED")));
        assertEquals(List.of("001", "002", "003", "004"), f.store.orders.stream().map(o -> o.odno()).toList());
        assertEquals("SUBMITTED", f.store.dispatch(Phase.ALL).status());
        assertTrue(f.notifier.messages.get(0).startsWith("[myrobo] 모의투자 예약주문 접수: 주문안 #4 4건"), f.notifier.messages.get(0));
    }

    @Test
    void failureCancelsWhatWasAlreadyAcceptedAndStops() {
        var f = new Fixture();
        f.kis.on(RESERVE, reserveOk("001"), reserveOk("002"), new KisTransport.Response(500, RESERVE_FAIL));
        f.kis.on(CANCEL, CANCEL_OK);
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.FAILED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertEquals(3, f.kis.to(RESERVE).size());
        // 나중에 접수된 것부터 취소 (SCHR 002 → BIL 001), 접수일자는 오늘 한국 날짜
        assertEquals(List.of("002", "001"), f.kis.to(CANCEL).stream().map(s -> field(s.body(), "OVRS_RSVN_ODNO")).toList());
        assertTrue(f.kis.to(CANCEL).stream().allMatch(s -> "20261102".equals(field(s.body(), "RSVN_ORD_RCIT_DT"))));
        assertEquals(List.of("VTTT3017U", "VTTT3017U"), f.sent(CANCEL, "tr_id"));
        assertEquals(List.of("CANCELLED", "CANCELLED", "FAILED"), f.store.orders.stream().map(o -> o.status()).toList());
        assertEquals(java.util.Arrays.asList("001", "002", null), f.store.orders.stream().map(o -> o.odno()).toList()); // 주문번호는 남긴다
        assertEquals("FAILED", f.store.dispatch(Phase.ALL).status());
        String msg = f.notifier.messages.get(0);
        assertTrue(msg.contains("VTI 매수 접수 실패: APBK1234") && msg.contains("먼저 접수된 주문을 취소했어요")
                && msg.contains("- 매도 BIL 12주") && msg.contains("나머지 1건은 보내지 않았어요") && !msg.contains("[중요]"), msg);
    }

    @Test
    void cancelFailureIsReportedForManualAction() {
        var f = new Fixture();
        f.kis.on(RESERVE, reserveOk("001"), new KisTransport.Response(500, RESERVE_FAIL));
        f.kis.on(CANCEL, new KisTransport.Response(500, CANCEL_FAIL));

        assertEquals(Outcome.FAILED, f.dispatcher(Mode.PAPER).tick());

        assertEquals(List.of("CANCEL_FAILED", "FAILED"), f.store.orders.stream().map(o -> o.status()).toList());
        String msg = f.notifier.messages.get(0);
        assertTrue(msg.contains("[중요] 아래 주문은 취소하지 못했어요") && msg.contains("- 매도 BIL 12주"), msg);
    }

    @Test
    void networkErrorWhileSendingCountsAsFailure() {
        var f = new Fixture();
        f.kis.on(RESERVE, new KisException("openapivts.koreainvestment.com/... 연결 실패: ConnectException"));

        assertEquals(Outcome.FAILED, f.dispatcher(Mode.PAPER).tick());

        assertEquals("FAILED", f.store.orders.get(0).status());
        assertEquals(1, f.store.orders.size());
        assertTrue(f.kis.to(CANCEL).isEmpty()); // 접수된 게 없으니 취소할 것도 없다
    }

    @Test
    void shortOfCashSellsFirstThenBuysOnTheNextTradingDay() {
        // 매수 $994.09, 매수 가능 $500 → 매도 예상 $1,089.36을 더하면 됨 → 오늘은 매도만
        var f = new Fixture().buyable("500.00", "2000.00");
        f.kis.on(RESERVE, reserveOk("001"), reserveOk("002"), reserveOk("003"), reserveOk("004"));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.SELLS_SUBMITTED, d.tick());
        assertEquals(Outcome.WAITING, d.tick()); // 같은 날은 매수하지 않는다
        assertEquals(List.of("VTTT3016U"), f.sent(RESERVE, "tr_id"));
        assertTrue(f.notifier.messages.get(0).contains("매도만 먼저 예약"), f.notifier.messages.get(0));

        f.nextDay(); // 11-03, 매도 대금이 들어와 매수 가능 $2,000
        assertEquals(Outcome.SUBMITTED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertEquals(List.of("VTTT3016U", "VTTT3014U", "VTTT3014U", "VTTT3014U"), f.sent(RESERVE, "tr_id"));
        assertEquals("SUBMITTED", f.store.dispatch(Phase.SELL).status());
        assertEquals("SUBMITTED", f.store.dispatch(Phase.BUY).status());
        assertEquals(List.of("BIL", "SCHR", "VTI", "GLDM"), f.store.orders.stream().map(o -> o.order().symbol()).toList());
        assertTrue(f.notifier.messages.get(1).contains("앞서 예약한 매도에 이어 매수"), f.notifier.messages.get(1));
    }

    @Test
    void buysWaitWhileTheSaleHasNotSettledAndWarnOnce() {
        var f = new Fixture().buyable("500.00");
        f.kis.on(RESERVE, reserveOk("001"));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);
        assertEquals(Outcome.SELLS_SUBMITTED, d.tick());

        f.nextDay();
        assertEquals(Outcome.WAITING_FUNDS, d.tick());
        assertEquals(Outcome.WAITING_FUNDS, d.tick());

        assertEquals(1, f.kis.to(RESERVE).size());
        assertEquals(2, f.notifier.messages.size()); // 매도 접수 1 + 매수 대기 1 (한 번만)
        assertTrue(f.notifier.messages.get(1).contains("매수를 기다려요") && !f.notifier.messages.get(1).contains("$"),
                f.notifier.messages.get(1));
    }

    @Test
    void failedSellStepIsNotContinued() {
        var f = new Fixture().buyable("500.00");
        f.kis.on(RESERVE, new KisTransport.Response(500, RESERVE_FAIL));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);
        assertEquals(Outcome.FAILED, d.tick());

        f.nextDay();
        assertEquals(Outcome.ALREADY, d.tick());

        assertEquals(1, f.kis.to(RESERVE).size());
    }

    @Test
    void notEnoughEvenWithSaleProceedsBlocksBeforeSendingAnything() {
        var f = new Fixture().buyable("500.00");
        f.open = plan(8, "SENT", null, List.of(
                new ItemView(1, "SELL", "BIL", 1, d("91.70"), d("91.70")),
                new ItemView(2, "BUY", "VTI", 3, d("371.57"), d("1114.71"))));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.BLOCKED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertTrue(f.kis.to(RESERVE).isEmpty());
        assertTrue(f.store.dispatch(Phase.ALL).message().contains("매도 예상 대금 $90.78를 더한 것보다 큼"),
                f.store.dispatch(Phase.ALL).message());
        assertEquals(1, f.notifier.messages.size());
    }

    @Test
    void buysOnlyPlanWithoutCashIsBlocked() {
        var f = new Fixture().buyable("100.00");
        f.open = plan(9, "SENT", null, List.of(new ItemView(1, "BUY", "VTI", 1, d("371.57"), d("371.57"))));

        assertEquals(Outcome.BLOCKED, f.dispatcher(Mode.PAPER).tick());

        assertTrue(f.store.dispatch(Phase.ALL).message().contains("매수 가능 금액 $100.00보다 큼"));
    }

    @Test
    void plannerProblemsBlockWithoutCallingTheBroker() {
        var f = new Fixture();
        f.open = plan(9, "SENT", 1000L, List.of(new ItemView(1, "BUY", "VTI", 1, d("371.57"), d("371.57"))));

        assertEquals(Outcome.BLOCKED, f.dispatcher(Mode.PAPER).tick());

        assertTrue(f.kis.sent.isEmpty());
        assertEquals("BLOCKED", f.store.dispatch(Phase.ALL).status());
    }

    @Test
    void lookupFailureIsRetriedLaterAndWarnedOnce() {
        var f = new Fixture();
        f.kis.on(BUYABLE, new KisException("연결 실패"));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.RETRY_LATER, d.tick());
        assertEquals(Outcome.RETRY_LATER, d.tick());

        assertTrue(f.store.dispatches.isEmpty());
        assertEquals(1, f.notifier.messages.size());
    }

    @Test
    void losingTheRaceSendsNothing() {
        var f = new Fixture();
        f.store.loseRace = true;

        assertEquals(Outcome.ALREADY, f.dispatcher(Mode.PAPER).tick());

        assertTrue(f.kis.to(RESERVE).isEmpty());
    }

    @Test
    void dryRunAndPaperAreTrackedSeparately() {
        var f = new Fixture();
        f.kis.on(RESERVE, reserveOk("001"));

        assertEquals(Outcome.DRY_RUN, f.dispatcher(Mode.DRY_RUN).tick());
        assertEquals(Outcome.SUBMITTED, f.dispatcher(Mode.PAPER).tick());
    }

    @Test
    void dryRunAlsoShowsTheSellFirstStep() {
        var f = new Fixture().buyable("500.00");

        assertEquals(Outcome.DRY_RUN, f.dispatcher(Mode.DRY_RUN).tick());

        assertEquals(Phase.SELL, f.store.dispatches.get(0).phase());
        assertEquals(List.of("BIL"), f.store.orders.stream().map(o -> o.order().symbol()).toList());
        assertTrue(f.notifier.messages.get(0).contains("매도만 먼저 예약"));
    }

    /** "KEY":"value" 한 개 꺼내기 (본문은 문자열 값만 있는 평평한 JSON). */
    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        int start = i + key.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }
}

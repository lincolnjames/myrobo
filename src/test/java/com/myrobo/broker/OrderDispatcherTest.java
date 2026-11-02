package com.myrobo.broker;

import com.myrobo.broker.BrokerFixtures.FakeKis;
import com.myrobo.broker.BrokerFixtures.MemoryStore;
import com.myrobo.broker.BrokerFixtures.RecordingNotifier;
import com.myrobo.broker.BrokerFixtures.TestClock;
import com.myrobo.broker.DispatchStore.Mode;
import com.myrobo.broker.OrderDispatcher.Outcome;
import com.myrobo.collector.MarketCalendar;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.myrobo.broker.BrokerFixtures.BUYABLE_100K;
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

/** 주문안 → 예약주문 전송 흐름: 한 번만, 매도 먼저, 실패하면 멈춤, 드라이런은 보내지 않음. */
class OrderDispatcherTest {

    private static final String RESERVE = KisClient.RESERVE_PATH;
    private static final String NOON_KST = "2026-11-02T03:00:00Z";

    private static final class Fixture {
        final FakeKis kis = new FakeKis().on(KisClient.TOKEN_PATH, TOKEN_OK).on(KisClient.BUYABLE_PATH, BUYABLE_100K);
        final MemoryStore store = new MemoryStore();
        final RecordingNotifier notifier = new RecordingNotifier();
        final TestClock clock = new TestClock(NOON_KST);
        PlanView open = plan4();

        OrderDispatcher dispatcher(Mode mode) {
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
            return new OrderDispatcher(mode, () -> c, store, src, notifier, limits(), clock);
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
        f.clock.now = java.time.Instant.parse("2026-11-01T23:30:00Z"); // KST 08:30

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
        assertEquals(1, f.kis.to(KisClient.BUYABLE_PATH).size()); // 조회는 해 본다
        assertEquals("DRY_RUN", f.store.dispatches.get(0).status());
        assertEquals(List.of("PLANNED", "PLANNED", "PLANNED", "PLANNED"), f.store.orders.stream().map(o -> o.status()).toList());
        assertEquals(1, f.notifier.messages.size());
        assertTrue(f.notifier.messages.get(0).contains("드라이런") && f.notifier.messages.get(0).contains("- 매도 BIL 12주 지정가 $90.78 (AMEX)"),
                f.notifier.messages.get(0));
    }

    @Test
    void paperSubmitsSellsFirstThenBuysOnce() {
        var f = new Fixture();
        f.kis.on(RESERVE, reserveOk("001"), reserveOk("002"), reserveOk("003"), reserveOk("004"));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.SUBMITTED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertEquals(List.of("VTTT3016U", "VTTT3014U", "VTTT3014U", "VTTT3014U"),
                f.kis.to(RESERVE).stream().map(s -> s.headers().get("tr_id")).toList());
        assertEquals(List.of("BIL", "SCHR", "VTI", "GLDM"), f.store.orders.stream().map(o -> o.order().symbol()).toList());
        assertTrue(f.store.orders.stream().allMatch(o -> o.status().equals("SUBMITTED")));
        assertEquals(List.of("001", "002", "003", "004"), f.store.orders.stream().map(o -> o.odno()).toList());
        assertEquals("SUBMITTED", f.store.dispatches.get(0).status());
        assertTrue(f.notifier.messages.get(0).startsWith("[myrobo] 모의투자 예약주문 접수: 주문안 #4 4건"), f.notifier.messages.get(0));
    }

    @Test
    void stopsAtTheFirstFailedOrderAndDoesNotRetry() {
        var f = new Fixture();
        f.kis.on(RESERVE, reserveOk("001"), new KisTransport.Response(500, RESERVE_FAIL));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.FAILED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertEquals(2, f.kis.to(RESERVE).size());
        assertEquals(List.of("SUBMITTED", "FAILED"), f.store.orders.stream().map(o -> o.status()).toList());
        assertEquals("FAILED", f.store.dispatches.get(0).status());
        String msg = f.notifier.messages.get(0);
        assertTrue(msg.contains("SCHR 매수 접수 실패: APBK1234") && msg.contains("- 매도 BIL 12주") && msg.contains("나머지 2건은 보내지 않았어요"), msg);
    }

    @Test
    void networkErrorWhileSendingCountsAsFailure() {
        var f = new Fixture();
        f.kis.on(RESERVE, new KisException("openapivts.koreainvestment.com/... 연결 실패: ConnectException"));

        assertEquals(Outcome.FAILED, f.dispatcher(Mode.PAPER).tick());

        assertEquals("FAILED", f.store.orders.get(0).status());
        assertEquals(1, f.store.orders.size());
    }

    @Test
    void notEnoughBuyingPowerBlocksBeforeSendingAnything() {
        var f = new Fixture();
        f.kis.scripts.remove(KisClient.BUYABLE_PATH);
        f.kis.on(KisClient.BUYABLE_PATH, buyable("500.00"));
        OrderDispatcher d = f.dispatcher(Mode.PAPER);

        assertEquals(Outcome.BLOCKED, d.tick());
        assertEquals(Outcome.ALREADY, d.tick());

        assertTrue(f.kis.to(RESERVE).isEmpty());
        assertTrue(f.store.dispatches.get(0).message().contains("매수 합계 $994.09가 매수 가능 금액 $500.00보다 큼"),
                f.store.dispatches.get(0).message());
        assertEquals(1, f.notifier.messages.size());
    }

    @Test
    void plannerProblemsBlockWithoutCallingTheBroker() {
        var f = new Fixture();
        f.open = plan(9, "SENT", 1000L, List.of(new ItemView(1, "BUY", "VTI", 1, d("371.57"), d("371.57"))));

        assertEquals(Outcome.BLOCKED, f.dispatcher(Mode.PAPER).tick());

        assertTrue(f.kis.sent.isEmpty());
        assertEquals("BLOCKED", f.store.dispatches.get(0).status());
    }

    @Test
    void lookupFailureIsRetriedLaterAndWarnedOnce() {
        var f = new Fixture();
        f.kis.scripts.remove(KisClient.BUYABLE_PATH);
        f.kis.on(KisClient.BUYABLE_PATH, new KisException("연결 실패"));
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
}

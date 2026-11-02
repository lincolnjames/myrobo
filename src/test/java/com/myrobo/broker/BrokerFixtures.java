package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Phase;
import com.myrobo.broker.OrderPlanner.Order;
import com.myrobo.collector.http.Sleeper;
import com.myrobo.ops.Notifier;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** broker 테스트용 가짜들. */
final class BrokerFixtures {

    static final String APP_KEY = "PSappkey-secret-1234";
    static final String APP_SECRET = "appsecret-very-secret-5678";
    static final String ACCOUNT = "50123456-01";

    static final String TOKEN_OK = """
            {"access_token":"tok-abc","access_token_token_expired":"2026-11-03 14:26:29","token_type":"Bearer","expires_in":86400}""";
    /** 2026-11-02 모의 계좌에서 실제로 받은 매수가능금액 응답 (VTI, 371.57). */
    static final String BUYABLE_100K = """
            {"output":{"tr_crcy_cd":"USD","ord_psbl_frcr_amt":"100000.00","sll_ruse_psbl_amt":"0.00",
            "ovrs_ord_psbl_amt":"100000.00","max_ord_psbl_qty":"266","echm_af_ord_psbl_amt":"100000.00",
            "echm_af_ord_psbl_qty":"266","ord_psbl_qty":"266","exrt":"1337.2000000000",
            "frcr_ord_psbl_amt1":"214227.778380","ovrs_max_ord_psbl_qty":"576"},
            "rt_cd":"0","msg_cd":"20310000","msg1":"모의투자 조회가 완료되었습니다.                                       "}""";

    static String buyable(String usd) {
        return BUYABLE_100K.replace("\"ord_psbl_frcr_amt\":\"100000.00\"", "\"ord_psbl_frcr_amt\":\"" + usd + "\"");
    }

    /** 2026-11-02 모의 서버의 실제 취소 성공 응답: rt_cd가 1이다 (msg1은 콘솔에서 깨져 보여 길이로 추정한 문구). */
    static final String CANCEL_OK = """
            {"rt_cd":"1","msg_cd":"40720000","msg1":"모의투자 예약주문 취소 완료"}""";
    /** 같은 예약을 다시 취소했을 때의 실제 응답. */
    static final String CANCEL_ALREADY = """
            {"rt_cd":"1","msg_cd":"40830000","msg1":"모의투자 정정/취소된 예약주문입니다."}""";
    static final String CANCEL_FAIL = """
            {"rt_cd":"1","msg_cd":"APBK0999","msg1":"취소할 주문이 없습니다."}""";

    static String reserveOk(String odno) {
        return "{\"rt_cd\":\"0\",\"msg_cd\":\"APBK0013\",\"msg1\":\"주문 전송 완료 되었습니다.\","
                + "\"output\":{\"ODNO\":\"" + odno + "\",\"RSVN_ORD_RCIT_DT\":\"20261102\"}}";
    }

    static final String RESERVE_FAIL = """
            {"rt_cd":"1","msg_cd":"APBK1234","msg1":"주문가능금액을 초과 하였습니다."}""";

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** 2026-11-02 주문안 #4와 같은 줄. */
    static PlanView plan4() {
        return plan(4, "SENT", null, List.of(
                new ItemView(10, "SELL", "BIL", 12, d("91.700000"), d("1100.40")),
                new ItemView(11, "BUY", "SCHR", 19, d("24.150000"), d("458.85")),
                new ItemView(12, "BUY", "VTI", 1, d("371.570000"), d("371.57")),
                new ItemView(13, "BUY", "GLDM", 2, d("76.830000"), d("153.66"))));
    }

    static PlanView plan(long id, String status, Long fxUsd, List<ItemView> items) {
        return new PlanView(id, LocalDate.of(2026, 11, 2), "INITIAL_BUILD", status, "FULL", "INITIAL_BUILD", null,
                d("9244.83"), d("22.55"), fxUsd, fxUsd == null ? null : 1_000_000L, LocalDate.of(2026, 11, 4), null,
                items, List.of());
    }

    static OrderPlanner.Limits limits() {
        return new OrderPlanner.Limits(d("1.0"), d("15000"), d("40000"),
                Map.of("VTI", "AMEX", "SCHR", "AMEX", "GLDM", "AMEX", "BIL", "AMEX"));
    }

    /** 바꿀 수 있는 시계. */
    static final class TestClock extends Clock {
        Instant now;

        TestClock(String utc) {
            now = Instant.parse(utc);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record Sent(String method, URI uri, Map<String, String> headers, String body) {
        String path() {
            return uri.getPath();
        }
    }

    /** 경로별로 준비한 응답을 차례로 돌려주는 가짜 KIS. */
    static final class FakeKis implements KisTransport {
        final List<Sent> sent = new ArrayList<>();
        final Map<String, Deque<Object>> scripts = new LinkedHashMap<>();

        FakeKis on(String path, Object... responses) {
            scripts.computeIfAbsent(path, k -> new ArrayDeque<>()).addAll(List.of(responses));
            return this;
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body) {
            sent.add(new Sent(method, uri, Map.copyOf(headers), body));
            Deque<Object> q = scripts.get(uri.getPath());
            if (q == null || q.isEmpty()) {
                throw new AssertionError("준비하지 않은 요청: " + method + " " + uri.getPath());
            }
            Object r = q.size() == 1 ? q.peek() : q.poll();
            if (r instanceof RuntimeException e) {
                throw e;
            }
            return r instanceof Response res ? res : new Response(200, (String) r);
        }

        List<Sent> to(String path) {
            return sent.stream().filter(s -> s.path().equals(path)).toList();
        }
    }

    static final class RecordingSleeper implements Sleeper {
        final List<Duration> slept = new ArrayList<>();

        @Override
        public void sleep(Duration d) {
            slept.add(d);
        }
    }

    static KisClient client(FakeKis t, Clock clock) {
        return new KisClient(t, KisClient.Env.PAPER, APP_KEY, APP_SECRET, ACCOUNT, clock, new RecordingSleeper());
    }

    static final class RecordingNotifier implements Notifier {
        final List<String> messages = new ArrayList<>();

        @Override
        public boolean send(String text) {
            messages.add(text);
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }
    }

    /** 메모리 전송 기록. 시도 날짜는 clock의 한국 날짜. */
    static final class MemoryStore implements DispatchStore {
        record Row(long id, long planId, Mode mode, Phase phase, String status, String message, LocalDate day) {
        }

        record OrderRow(long id, long dispatchId, Order order, String status, String odno, String msg) {
        }

        final List<Row> dispatches = new ArrayList<>();
        final List<OrderRow> orders = new ArrayList<>();
        final Clock clock;
        BigDecimal submittedToday = BigDecimal.ZERO;
        boolean loseRace;

        MemoryStore(Clock clock) {
            this.clock = clock;
        }

        @Override
        public List<Attempt> attempts(long planId, Mode mode) {
            return dispatches.stream().filter(r -> r.planId() == planId && r.mode() == mode)
                    .map(r -> new Attempt(r.id(), r.phase(), r.status(), r.day())).toList();
        }

        @Override
        public Optional<Long> start(long planId, Mode mode, Phase phase) {
            if (loseRace || dispatches.stream().anyMatch(r -> r.planId() == planId && r.mode() == mode && r.phase() == phase)) {
                return Optional.empty();
            }
            long id = dispatches.size() + 1;
            dispatches.add(new Row(id, planId, mode, phase, "PENDING", null,
                    clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()));
            return Optional.of(id);
        }

        @Override
        public void cancelResult(long orderId, String status, KisClient.Reply r) {
            OrderRow o = orders.get((int) orderId - 1);
            orders.set((int) orderId - 1, new OrderRow(o.id(), o.dispatchId(), o.order(), status, o.odno(),
                    "취소: " + (r == null ? "" : r.summary())));
        }

        Row dispatch(Phase phase) {
            return dispatches.stream().filter(r -> r.phase() == phase).findFirst().orElseThrow();
        }

        @Override
        public long addOrder(long dispatchId, Order o, String status) {
            long id = orders.size() + 1;
            orders.add(new OrderRow(id, dispatchId, o, status, null, null));
            return id;
        }

        @Override
        public void orderResult(long orderId, String status, KisClient.Reply r) {
            OrderRow o = orders.get((int) orderId - 1);
            orders.set((int) orderId - 1, new OrderRow(o.id(), o.dispatchId(), o.order(), status,
                    r == null ? null : r.field("odno"), r == null ? null : r.message()));
        }

        @Override
        public void finish(long dispatchId, String status, String message) {
            Row r = dispatches.get((int) dispatchId - 1);
            dispatches.set((int) dispatchId - 1, new Row(r.id(), r.planId(), r.mode(), r.phase(), status, message, r.day()));
        }

        @Override
        public BigDecimal submittedUsdOn(LocalDate kstDay) {
            return submittedToday;
        }
    }

    private BrokerFixtures() {
    }
}

package com.myrobo.broker;

import com.myrobo.broker.BrokerFixtures.FakeKis;
import com.myrobo.broker.BrokerFixtures.RecordingSleeper;
import com.myrobo.broker.BrokerFixtures.Sent;
import com.myrobo.broker.BrokerFixtures.TestClock;
import com.myrobo.broker.OrderPlanner.Side;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.myrobo.broker.BrokerFixtures.ACCOUNT;
import static com.myrobo.broker.BrokerFixtures.APP_KEY;
import static com.myrobo.broker.BrokerFixtures.APP_SECRET;
import static com.myrobo.broker.BrokerFixtures.BUYABLE_100K;
import static com.myrobo.broker.BrokerFixtures.RESERVE_FAIL;
import static com.myrobo.broker.BrokerFixtures.TOKEN_OK;
import static com.myrobo.broker.BrokerFixtures.client;
import static com.myrobo.broker.BrokerFixtures.d;
import static com.myrobo.broker.BrokerFixtures.reserveOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KIS 요청 형식: 모의 서버·TR ID, 헤더, 본문, 토큰 재사용·발급 간격, 응답 읽기, 비밀값이 메시지에 안 남는지. */
class KisClientTest {

    private static final String TOKEN = KisClient.TOKEN_PATH;
    private static final String BUYABLE = KisClient.BUYABLE_PATH;
    private static final String RESERVE = KisClient.RESERVE_PATH;

    @Test
    void readsTheBuyableAmountFromTheRealPaperResponse() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(BUYABLE, BUYABLE_100K);

        KisClient.Buyable b = client(t, new TestClock("2026-11-02T03:00:00Z")).buyable("AMEX", "VTI", d("371.57"));

        assertEquals(0, d("100000.00").compareTo(b.orderableUsd()));
        assertEquals(266, b.maxQty());
        Sent q = t.to(BUYABLE).get(0);
        assertEquals("openapivts.koreainvestment.com", q.uri().getHost());
        assertEquals(29443, q.uri().getPort());
        assertEquals("VTTS3007R", q.headers().get("tr_id"));
        assertEquals("CANO=50123456&ACNT_PRDT_CD=01&OVRS_EXCG_CD=AMEX&OVRS_ORD_UNPR=371.57&ITEM_CD=VTI", q.uri().getQuery());
    }

    @Test
    void reservationUsesPaperTrIdsAndLimitOrderBody() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, reserveOk("0030001"), reserveOk("0030002"));
        KisClient c = client(t, new TestClock("2026-11-02T03:00:00Z"));

        KisClient.Reply buy = c.reserve(Side.BUY, "AMEX", "SCHR", 19, d("24.40"));
        c.reserve(Side.SELL, "AMEX", "BIL", 12, d("90.78"));

        assertTrue(buy.ok());
        assertEquals("0030001", buy.field("odno"));
        assertEquals("20261102", buy.field("rsvn_ord_rcit_dt"));
        Sent first = t.to(RESERVE).get(0);
        assertEquals("POST", first.method());
        assertEquals("VTTT3014U", first.headers().get("tr_id"));
        assertEquals("VTTT3016U", t.to(RESERVE).get(1).headers().get("tr_id"));
        assertEquals("{\"CANO\":\"50123456\",\"ACNT_PRDT_CD\":\"01\",\"PDNO\":\"SCHR\",\"OVRS_EXCG_CD\":\"AMEX\","
                + "\"FT_ORD_QTY\":\"19\",\"FT_ORD_UNPR3\":\"24.40\",\"ORD_SVR_DVSN_CD\":\"0\",\"ORD_DVSN\":\"00\"}", first.body());
        assertEquals("Bearer tok-abc", first.headers().get("authorization"));
        assertEquals(APP_KEY, first.headers().get("appkey"));
        assertEquals("P", first.headers().get("custtype"));
    }

    @Test
    void tokenIsIssuedOnceAndReused() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(BUYABLE, BUYABLE_100K);
        var clock = new TestClock("2026-11-02T03:00:00Z");
        KisClient c = client(t, clock);

        c.buyable("AMEX", "VTI", d("371.57"));
        clock.now = clock.now.plus(Duration.ofHours(10));
        c.buyable("AMEX", "VTI", d("371.57"));

        assertEquals(1, t.to(TOKEN).size());
        assertEquals("{\"grant_type\":\"client_credentials\",\"appkey\":\"" + APP_KEY + "\",\"appsecret\":\"" + APP_SECRET + "\"}",
                t.to(TOKEN).get(0).body());
    }

    @Test
    void tokenIsRenewedAnHourBeforeItExpires() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(BUYABLE, BUYABLE_100K);
        var clock = new TestClock("2026-11-02T03:00:00Z");  // 토큰 만료 11-03 14:26 KST = 05:26Z
        KisClient c = client(t, clock);

        c.buyable("AMEX", "VTI", d("371.57"));
        clock.now = java.time.Instant.parse("2026-11-03T04:30:00Z"); // 만료 1시간 안
        c.buyable("AMEX", "VTI", d("371.57"));

        assertEquals(2, t.to(TOKEN).size());
    }

    @Test
    void failedTokenIsNotRequestedAgainWithinAMinute() {
        var t = new FakeKis().on(TOKEN, new KisTransport.Response(403,
                "{\"error_description\":\"유효하지 않은 AppKey입니다.\",\"error_code\":\"EGW00103\"}"));
        var clock = new TestClock("2026-11-02T03:00:00Z");
        KisClient c = client(t, clock);

        KisException first = assertThrows(KisException.class, () -> c.buyable("AMEX", "VTI", d("371.57")));
        KisException second = assertThrows(KisException.class, () -> c.buyable("AMEX", "VTI", d("371.57")));

        assertTrue(first.getMessage().contains("EGW00103"), first.getMessage());
        assertTrue(second.getMessage().contains("1분"), second.getMessage());
        assertEquals(1, t.to(TOKEN).size());
        clock.now = clock.now.plusSeconds(62);
        assertThrows(KisException.class, () -> c.buyable("AMEX", "VTI", d("371.57")));
        assertEquals(2, t.to(TOKEN).size());
    }

    @Test
    void businessErrorIsAReplyNotAnException() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, new KisTransport.Response(500, RESERVE_FAIL));

        KisClient.Reply r = client(t, new TestClock("2026-11-02T03:00:00Z")).reserve(Side.BUY, "AMEX", "VTI", 1, d("375.29"));

        assertFalse(r.ok());
        assertEquals(500, r.httpStatus());
        assertEquals("APBK1234 주문가능금액을 초과 하였습니다.", r.summary());
    }

    @Test
    void unreadableBodyIsAFailedReply() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, new KisTransport.Response(502, "<html>bad gateway</html>"));

        KisClient.Reply r = client(t, new TestClock("2026-11-02T03:00:00Z")).reserve(Side.BUY, "AMEX", "VTI", 1, d("375.29"));

        assertFalse(r.ok());
        assertEquals("HTTP 502", r.summary());
    }

    @Test
    void callsArePacedForThePaperRateLimit() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(BUYABLE, BUYABLE_100K);
        var sleeper = new RecordingSleeper();
        var c = new KisClient(t, KisClient.Env.PAPER, APP_KEY, APP_SECRET, ACCOUNT,
                new TestClock("2026-11-02T03:00:00Z"), sleeper);

        c.buyable("AMEX", "VTI", d("371.57")); // 토큰 + 조회

        assertEquals(java.util.List.of(KisClient.PACE), sleeper.slept);
    }

    @Test
    void paceCountsFromTheResponseNotTheRequest() {
        // 2026-11-02: BIL 응답이 2초 넘게 걸린 뒤 1.1초(보낸 시각 기준)만 지나 보낸 GLDM이 EGW00201로 거절됨
        var clock = new TestClock("2026-11-02T03:00:00Z");
        var fake = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, reserveOk("1"), reserveOk("2"));
        KisTransport slow = (m, u, h, b) -> {
            KisTransport.Response r = fake.send(m, u, h, b);
            clock.now = clock.now.plusSeconds(2); // 응답이 2초 걸림
            return r;
        };
        var sleeper = new RecordingSleeper();
        var c = new KisClient(slow, KisClient.Env.PAPER, APP_KEY, APP_SECRET, ACCOUNT, clock, sleeper);

        c.reserve(Side.BUY, "AMEX", "VTI", 1, d("375.29"));
        c.reserve(Side.BUY, "AMEX", "GLDM", 1, d("77.60"));

        assertEquals(java.util.List.of(KisClient.PACE, KisClient.PACE), sleeper.slept); // 토큰 뒤, 첫 주문 뒤
    }

    /** 2026-11-02 모의 서버가 실제로 돌려준 본문. */
    private static final String RATE_LIMITED = """
            {"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다.","msg_cd":"EGW00201","message":"EGW00201"}""";

    @Test
    void rateLimitedRequestIsRetriedAfterABreak() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, new KisTransport.Response(500, RATE_LIMITED), reserveOk("0030009"));
        var sleeper = new RecordingSleeper();
        var c = new KisClient(t, KisClient.Env.PAPER, APP_KEY, APP_SECRET, ACCOUNT, new TestClock("2026-11-02T03:00:00Z"), sleeper);

        KisClient.Reply r = c.reserve(Side.BUY, "AMEX", "GLDM", 36, d("77.60"));

        assertTrue(r.ok());
        assertEquals("0030009", r.field("odno"));
        assertEquals(2, t.to(RESERVE).size());
        assertTrue(sleeper.slept.contains(KisClient.RATE_LIMIT_BACKOFF), sleeper.slept.toString());
    }

    @Test
    void rateLimitGivesUpAfterThreeTries() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, new KisTransport.Response(500, RATE_LIMITED));

        KisClient.Reply r = client(t, new TestClock("2026-11-02T03:00:00Z")).reserve(Side.BUY, "AMEX", "GLDM", 36, d("77.60"));

        assertFalse(r.ok());
        assertEquals("EGW00201", r.msgCd());
        assertEquals(KisClient.RATE_LIMIT_ATTEMPTS, t.to(RESERVE).size());
    }

    @Test
    void otherErrorsAreNotRetried() {
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE, new KisTransport.Response(500, RESERVE_FAIL));

        client(t, new TestClock("2026-11-02T03:00:00Z")).reserve(Side.BUY, "AMEX", "VTI", 1, d("375.29"));

        assertEquals(1, t.to(RESERVE).size());
    }

    @Test
    void readsTheRealPaperReservationReply() {
        // 2026-11-02 모의 예약주문 접수 응답 (ODNO만 오고 접수일자는 없음)
        var t = new FakeKis().on(TOKEN, TOKEN_OK).on(RESERVE,
                "{\"rt_cd\":\"0\",\"msg_cd\":\"40470000\",\"msg1\":\"모의투자 예약주문 완료\",\"output\":{\"ODNO\":\"267\"}}");

        KisClient.Reply r = client(t, new TestClock("2026-11-02T03:00:00Z")).reserve(Side.BUY, "AMEX", "VTI", 26, d("375.29"));

        assertTrue(r.ok());
        assertEquals("267", r.field("odno"));
        assertEquals(null, r.field("rsvn_ord_rcit_dt"));
    }

    @Test
    void secretsNeverAppearInErrors() {
        var t = new FakeKis().on(TOKEN, new KisTransport.Response(401, "{}"));

        KisException e = assertThrows(KisException.class,
                () -> client(t, new TestClock("2026-11-02T03:00:00Z")).buyable("AMEX", "VTI", d("371.57")));

        assertFalse(e.getMessage().contains(APP_KEY) || e.getMessage().contains(APP_SECRET) || e.getMessage().contains("50123456"));
    }

    @Test
    void rejectsMalformedAccountWithoutEchoingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new KisClient(new FakeKis(),
                KisClient.Env.PAPER, APP_KEY, APP_SECRET, "1234-5678", new TestClock("2026-11-02T03:00:00Z"), new RecordingSleeper()));

        assertFalse(e.getMessage().contains("1234-5678"));
    }

    @Test
    void liveUsesRealTrIds() {
        var c = new KisClient(new FakeKis(), KisClient.Env.LIVE, APP_KEY, APP_SECRET, ACCOUNT,
                new TestClock("2026-11-02T03:00:00Z"), new RecordingSleeper());

        assertEquals("TTTT3014U", c.trId(KisClient.RESERVE_BUY_TR));
    }
}

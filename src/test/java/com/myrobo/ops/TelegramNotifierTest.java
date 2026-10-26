package com.myrobo.ops;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 알림 통로: 실패해도 던지지 않고, 길이를 맞추고, 토큰을 가린다. */
class TelegramNotifierTest {

    private static final String TOKEN = "123456:ABC-secret-token";

    @Test
    void sendsTheText() {
        List<String> got = new ArrayList<>();

        boolean ok = new TelegramNotifier(got::add, TOKEN).send("안녕");

        assertTrue(ok);
        assertEquals(List.of("안녕"), got);
    }

    @Test
    void aFailingSenderNeverThrowsToTheJob() {
        var n = new TelegramNotifier(t -> { throw new IllegalStateException("텔레그램 응답 HTTP 401"); }, TOKEN);

        assertFalse(n.send("x"));
    }

    @Test
    void overlongTextIsCutToTelegramsLimit() {
        List<String> got = new ArrayList<>();

        new TelegramNotifier(got::add, TOKEN).send("가".repeat(10_000));

        assertEquals(TelegramNotifier.MAX_LENGTH, got.get(0).length());
        assertTrue(got.get(0).endsWith("(길어서 잘림)"));
    }

    @Test
    void theTokenIsMaskedInFailureDescriptions() {
        var e = new IllegalStateException("POST https://api.telegram.org/bot" + TOKEN + "/sendMessage 실패");

        String d = TelegramNotifier.describeFailure(e, TOKEN);

        assertFalse(d.contains(TOKEN), d);
        assertFalse(d.contains("secret"), d);
        assertTrue(d.contains("IllegalStateException"), d);
    }

    @Test
    void noopNotifierSendsNothingAndSaysItIsOff() {
        var n = new NoopNotifier();

        assertFalse(n.enabled());
        assertFalse(n.send("x"));
    }
}

package com.myrobo.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 텔레그램으로 알린다. 전송은 {@link Sender}에 맡겨 테스트에서 가짜로 바꿔 끼운다.
 * 봇 토큰은 URL 경로에 들어가므로, 실패 사유를 로그에 남길 때 토큰을 가린다.
 */
public final class TelegramNotifier implements Notifier {

    /** 텔레그램 메시지 한 건의 글자 수 한도. */
    static final int MAX_LENGTH = 4096;
    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    /** 실제 전송. 실패하면 예외를 던진다. */
    @FunctionalInterface
    public interface Sender {
        void send(String text);
    }

    private final Sender sender;
    private final String token;

    public TelegramNotifier(Sender sender, String token) {
        this.sender = sender;
        this.token = token;
    }

    @Override
    public boolean send(String text) {
        try {
            sender.send(truncate(text));
            return true;
        } catch (RuntimeException e) {
            log.warn("알림 전송 실패: {}", describeFailure(e, token));
            return false;
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        String tail = "\n…(길어서 잘림)";
        return text.substring(0, MAX_LENGTH - tail.length()) + tail;
    }

    /** 예외 종류와 메시지. 토큰이 섞여 있으면 가린다. */
    static String describeFailure(RuntimeException e, String token) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (token != null && !token.isBlank()) {
            msg = msg.replace(token, "***");
        }
        return e.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }
}

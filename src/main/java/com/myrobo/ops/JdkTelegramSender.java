package com.myrobo.ops;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JDK 내장 HttpClient로 텔레그램 sendMessage를 부른다. 토큰이 URL 경로에 들어가는 API라서,
 * 실패 사유에는 상태 코드와 예외 종류만 담고 URL은 절대 담지 않는다.
 */
public final class JdkTelegramSender implements TelegramNotifier.Sender {

    private static final String BASE = "https://api.telegram.org/bot";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;
    private final String chatId;

    public JdkTelegramSender(String token, String chatId) {
        this.endpoint = URI.create(BASE + token + "/sendMessage");
        this.chatId = chatId;
    }

    @Override
    public void send(String text) {
        String form = "chat_id=" + enc(chatId) + "&text=" + enc(text) + "&disable_web_page_preview=true";
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("텔레그램 응답 HTTP " + res.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("텔레그램 연결 실패 (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("텔레그램 전송이 중단됨");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

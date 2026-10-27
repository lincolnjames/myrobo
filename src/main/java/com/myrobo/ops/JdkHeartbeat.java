package com.myrobo.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * healthchecks.io 방식의 핑: 성공은 URL로, 실패는 URL/fail로 보낸다.
 * URL에 비밀 키가 들어 있으므로 로그와 예외에 URL을 담지 않는다.
 */
public final class JdkHeartbeat implements Heartbeat {

    private static final Logger log = LoggerFactory.getLogger(JdkHeartbeat.class);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI success;
    private final URI failure;

    public JdkHeartbeat(String url) {
        String base = url.strip().replaceAll("/+$", "");
        this.success = URI.create(base);
        this.failure = URI.create(base + "/fail");
    }

    @Override
    public void success() {
        ping(success, "");
    }

    @Override
    public void failure(String reason) {
        ping(failure, reason);
    }

    private void ping(URI target, String body) {
        HttpRequest req = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                log.warn("완료 신호 응답 HTTP {}", res.statusCode());
            }
        } catch (IOException e) {
            log.warn("완료 신호 전송 실패 ({})", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("완료 신호 전송이 중단됨");
        } catch (RuntimeException e) {
            log.warn("완료 신호 전송 실패 ({})", e.getClass().getSimpleName());
        }
    }
}

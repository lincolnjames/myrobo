package com.myrobo.broker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JDK HttpClient 구현. 다시 시도하지 않는다 (주문 중복 방지). 오류 메시지에는 호스트와 경로만 남긴다. */
public final class JdkKisTransport implements KisTransport {

    private final HttpClient client;
    private final Duration timeout;

    public JdkKisTransport(Duration timeout) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.timeout = timeout;
    }

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        if ("POST".equals(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        try {
            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(res.statusCode(), res.body());
        } catch (IOException e) {
            throw new KisException(uri.getHost() + uri.getPath() + " 연결 실패: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KisException(uri.getHost() + uri.getPath() + " 요청이 중단됨", e);
        }
    }
}

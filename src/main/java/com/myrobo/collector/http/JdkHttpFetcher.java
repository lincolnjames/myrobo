package com.myrobo.collector.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * JDK 내장 HttpClient 구현. 429·5xx·네트워크 오류는 잠깐 쉬었다가 다시 시도한다.
 * 4xx(429 제외)는 다시 해도 같은 결과라 바로 실패시킨다.
 */
public final class JdkHttpFetcher implements HttpFetcher {

    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient client;
    private final Duration timeout;
    private final Sleeper sleeper;

    public JdkHttpFetcher(Duration timeout, Sleeper sleeper) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.timeout = timeout;
        this.sleeper = sleeper;
    }

    @Override
    public String get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("Accept", "application/json");
        headers.forEach(b::header);
        HttpRequest request = b.build();

        FetchException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> res = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int s = res.statusCode();
                if (s >= 200 && s < 300) {
                    return res.body();
                }
                last = new FetchException(s, describe(uri, s, res.body()));
                if (s != 429 && s < 500) {
                    throw last;
                }
            } catch (IOException e) {
                last = new FetchException(uri.getHost() + " 연결 실패: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(uri.getHost() + " 요청이 중단됨", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleeper.sleep(Duration.ofSeconds(5L * attempt));
            }
        }
        throw last;
    }

    /** 쿼리스트링은 빼고 기록한다 (혹시 키가 들어 있어도 남지 않게). */
    private static String describe(URI uri, int status, String body) {
        String shortBody = body == null ? "" : body.replaceAll("\\s+", " ");
        if (shortBody.length() > 200) {
            shortBody = shortBody.substring(0, 200) + "…";
        }
        return uri.getHost() + uri.getPath() + " → HTTP " + status + " " + shortBody;
    }
}

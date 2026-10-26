package com.myrobo.collector.source;

import com.myrobo.collector.CollectorTypes.DailyBar;
import com.myrobo.collector.http.HttpFetcher;
import com.myrobo.collector.http.HttpFetcher.FetchException;
import com.myrobo.collector.http.RateLimiter;
import com.myrobo.common.json.Json;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tiingo 일봉 (수정주가·분할·배당 포함).
 * GET {base}/tiingo/daily/{ticker}/prices?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD, 헤더 Authorization: Token {key}
 */
public final class TiingoClient {

    private final HttpFetcher http;
    private final RateLimiter limiter;
    private final String baseUrl;
    private final String apiKey;

    public TiingoClient(HttpFetcher http, RateLimiter limiter, String baseUrl, String apiKey) {
        this.http = http;
        this.limiter = limiter;
        this.baseUrl = stripSlash(baseUrl);
        this.apiKey = apiKey;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public List<DailyBar> daily(String symbol, LocalDate start, LocalDate end) {
        if (!configured()) {
            throw new FetchException(0, "TIINGO_KEY가 설정되지 않음");
        }
        limiter.acquire();
        URI uri = URI.create(baseUrl + "/tiingo/daily/"
                + URLEncoder.encode(symbol.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8)
                + "/prices?startDate=" + start + "&endDate=" + end);
        String body = http.get(uri, Map.of("Authorization", "Token " + apiKey, "Content-Type", "application/json"));
        return parse(symbol, body);
    }

    @SuppressWarnings("unchecked")
    static List<DailyBar> parse(String symbol, String body) {
        Object root = Json.parse(body);
        if (root instanceof Map<?, ?> m) { // 오류는 {"detail": "..."} 형태로 온다
            throw new FetchException(200, "Tiingo " + symbol + ": " + m.get("detail"));
        }
        List<DailyBar> bars = new ArrayList<>();
        for (Object o : (List<Object>) root) {
            Map<String, Object> r = (Map<String, Object>) o;
            String date = (String) r.get("date");
            if (date == null || r.get("close") == null || r.get("adjClose") == null) {
                throw new FetchException(200, "Tiingo " + symbol + ": 필수 필드 누락 " + r);
            }
            bars.add(new DailyBar(
                    symbol,
                    LocalDate.parse(date.substring(0, 10)),
                    dec(r.get("open")),
                    dec(r.get("high")),
                    dec(r.get("low")),
                    dec(r.get("close")),
                    dec(r.get("adjClose")),
                    r.get("volume") == null ? null : dec(r.get("volume")).longValue(),
                    orDefault(dec(r.get("divCash")), BigDecimal.ZERO),
                    orDefault(dec(r.get("splitFactor")), BigDecimal.ONE)));
        }
        return bars;
    }

    private static BigDecimal dec(Object o) {
        return o == null ? null : (BigDecimal) o;
    }

    private static BigDecimal orDefault(BigDecimal v, BigDecimal d) {
        return v == null ? d : v;
    }

    static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

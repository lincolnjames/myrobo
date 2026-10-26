package com.myrobo.collector.source;

import com.myrobo.collector.CollectorTypes.MarketHoliday;
import com.myrobo.collector.http.HttpFetcher;
import com.myrobo.collector.http.HttpFetcher.FetchException;
import com.myrobo.common.json.Json;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finnhub 미국 시장 휴장일 (무료 플랜에서 확인됨).
 * GET {base}/stock/market-holiday?exchange=US, 헤더 X-Finnhub-Token: {key}
 */
public final class FinnhubClient {

    private final HttpFetcher http;
    private final String baseUrl;
    private final String apiKey;

    public FinnhubClient(HttpFetcher http, String baseUrl, String apiKey) {
        this.http = http;
        this.baseUrl = TiingoClient.stripSlash(baseUrl);
        this.apiKey = apiKey;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public List<MarketHoliday> usMarketHolidays() {
        if (!configured()) {
            throw new FetchException(0, "FINNHUB_KEY가 설정되지 않음");
        }
        String body = http.get(URI.create(baseUrl + "/stock/market-holiday?exchange=US"),
                Map.of("X-Finnhub-Token", apiKey));
        return parse(body);
    }

    @SuppressWarnings("unchecked")
    static List<MarketHoliday> parse(String body) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(body);
        if (root.containsKey("error")) {
            throw new FetchException(200, "Finnhub: " + root.get("error"));
        }
        List<MarketHoliday> out = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("data", List.of())) {
            Map<String, Object> r = (Map<String, Object>) o;
            out.add(new MarketHoliday(
                    LocalDate.parse((String) r.get("atDate")),
                    (String) r.get("eventName"),
                    (String) r.getOrDefault("tradingHour", "")));
        }
        return out;
    }
}

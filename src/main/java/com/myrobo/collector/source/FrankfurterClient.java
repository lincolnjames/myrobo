package com.myrobo.collector.source;

import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.http.HttpFetcher;
import com.myrobo.collector.http.HttpFetcher.FetchException;
import com.myrobo.common.json.Json;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;

/**
 * Frankfurter 환율 (키 불필요, 수출입은행 예비용).
 * GET {base}/v2/rate/USD/KRW → {"date":"YYYY-MM-DD","base":"USD","quote":"KRW","rate":1385.2}
 */
public final class FrankfurterClient {

    public static final String SOURCE = "FRANKFURTER";

    private final HttpFetcher http;
    private final String baseUrl;

    public FrankfurterClient(HttpFetcher http, String baseUrl) {
        this.http = http;
        this.baseUrl = TiingoClient.stripSlash(baseUrl);
    }

    public FxQuote usdKrw() {
        return parse(http.get(URI.create(baseUrl + "/v2/rate/USD/KRW"), Map.of()));
    }

    @SuppressWarnings("unchecked")
    static FxQuote parse(String body) {
        Map<String, Object> r = (Map<String, Object>) Json.parse(body);
        if (!"KRW".equalsIgnoreCase(String.valueOf(r.get("quote"))) || !(r.get("rate") instanceof BigDecimal rate)) {
            throw new FetchException(200, "Frankfurter: 예상과 다른 응답 " + r);
        }
        return new FxQuote(LocalDate.parse((String) r.get("date")), rate, SOURCE);
    }
}

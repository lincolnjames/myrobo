package com.myrobo.collector.source;

import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.http.HttpFetcher;
import com.myrobo.collector.http.HttpFetcher.FetchException;
import com.myrobo.common.json.Json;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한국수출입은행 현재환율 API (AP01). 영업일 오전 11시경 고시, 주말·공휴일·고시 전에는 빈 배열.
 * GET {base}?authkey={key}&searchdate=YYYYMMDD&data=AP01
 * 이 API는 키를 쿼리로만 받으므로, 오류 메시지에는 쿼리를 남기지 않는다 (JdkHttpFetcher).
 * 2025-06에 호스트가 oapi.koreaexim.go.kr로 바뀌었다. 바뀌면 설정의 base-url만 고친다.
 */
public final class KoreaEximClient {

    public static final String SOURCE = "KOREAEXIM";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final HttpFetcher http;
    private final String baseUrl;
    private final String apiKey;

    public KoreaEximClient(HttpFetcher http, String baseUrl, String apiKey) {
        this.http = http;
        this.baseUrl = TiingoClient.stripSlash(baseUrl);
        this.apiKey = apiKey;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 그날 고시된 달러 매매기준율. 고시가 없으면 empty. */
    public Optional<FxQuote> usdKrw(LocalDate date) {
        URI uri = URI.create(baseUrl + "?authkey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&searchdate=" + date.format(YYYYMMDD) + "&data=AP01");
        return parse(date, http.get(uri, Map.of()));
    }

    @SuppressWarnings("unchecked")
    static Optional<FxQuote> parse(LocalDate date, String body) {
        Object root = Json.parse(body);
        if (!(root instanceof List<?> rows)) {
            throw new FetchException(200, "수출입은행: 예상과 다른 응답 형식");
        }
        for (Object o : rows) {
            Map<String, Object> r = (Map<String, Object>) o;
            Object result = r.get("result");
            if (result != null && !"1".equals(result.toString())) {
                // 2: DATA 코드 오류, 3: 인증코드 오류, 4: 일일 호출 한도 초과
                throw new FetchException(200, "수출입은행 result=" + result);
            }
            if ("USD".equals(r.get("cur_unit"))) {
                String rate = String.valueOf(r.get("deal_bas_r")).replace(",", "").trim();
                return Optional.of(new FxQuote(date, new BigDecimal(rate), SOURCE));
            }
        }
        return Optional.empty();
    }
}

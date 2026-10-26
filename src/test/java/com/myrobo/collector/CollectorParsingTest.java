package com.myrobo.collector;

import com.myrobo.collector.CollectorTypes.DailyBar;
import com.myrobo.collector.Fakes.FakeHttp;
import com.myrobo.collector.Fakes.MutableClock;
import com.myrobo.collector.http.HttpFetcher.FetchException;
import com.myrobo.collector.http.RateLimiter;
import com.myrobo.collector.source.FinnhubClient;
import com.myrobo.collector.source.FrankfurterClient;
import com.myrobo.collector.source.KoreaEximClient;
import com.myrobo.collector.source.TiingoClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 외부 API 응답을 정확히 읽는지. 응답 예시는 실제 API 형식을 따른다. */
class CollectorParsingTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T23:00:00Z"));

    @Test
    void tiingoReadsSplitDayExactly() {
        // 2024-10-11 SCHB 3:1 분할 (사용자 PC에서 splitFactor 3.0 확인)
        FakeHttp http = new FakeHttp().on("/tiingo/daily/schb/prices", Fakes.array(
                Fakes.tiingoBar("2024-10-10", "66.39", "21.98", "0.0", "1.0"),
                Fakes.tiingoBar("2024-10-11", "22.21", "22.05", "0.0", "3.0")));
        TiingoClient tiingo = new TiingoClient(http, new RateLimiter("tiingo", 45, 900, clock, clock),
                "https://api.tiingo.com/", "secret-key");

        List<DailyBar> bars = tiingo.daily("SCHB", LocalDate.of(2024, 10, 10), LocalDate.of(2024, 10, 11));

        assertEquals(2, bars.size());
        DailyBar split = bars.get(1);
        assertEquals(LocalDate.of(2024, 10, 11), split.date());
        assertEquals(new BigDecimal("22.21"), split.close());
        assertEquals(new BigDecimal("22.05"), split.adjClose());
        assertTrue(split.isSplit());
        assertFalse(bars.get(0).hasCorporateAction());
        // 키는 URL이 아니라 헤더로
        String url = http.requests.get(0).toString();
        assertFalse(url.contains("secret-key"), url);
        assertEquals("Token secret-key", http.headers.get(0).get("Authorization"));
        assertTrue(url.contains("startDate=2024-10-10") && url.contains("endDate=2024-10-11"), url);
    }

    @Test
    void tiingoErrorDetailBecomesException() {
        FakeHttp http = new FakeHttp().on("/prices", "{\"detail\":\"Error: Ticker 'NOPE' not found\"}");
        TiingoClient tiingo = new TiingoClient(http, new RateLimiter("t", 45, 900, clock, clock),
                "https://api.tiingo.com", "k");

        FetchException e = assertThrows(FetchException.class,
                () -> tiingo.daily("NOPE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    void tiingoWithoutKeyFailsBeforeCalling() {
        FakeHttp http = new FakeHttp();
        TiingoClient tiingo = new TiingoClient(http, new RateLimiter("t", 45, 900, clock, clock),
                "https://api.tiingo.com", "");

        assertThrows(FetchException.class, () -> tiingo.daily("VTI", LocalDate.now(), LocalDate.now()));
        assertEquals(0, http.requests.size());
    }

    @Test
    void finnhubSeparatesFullClosuresFromEarlyCloses() {
        FakeHttp http = new FakeHttp().on("/stock/market-holiday", """
                {"data":[
                  {"eventName":"Christmas","atDate":"2026-12-25","tradingHour":""},
                  {"eventName":"Christmas Eve","atDate":"2026-12-24","tradingHour":"09:30-13:00"}
                ],"exchange":"US","timezone":"America/New_York"}""");
        var list = new FinnhubClient(http, "https://finnhub.io/api/v1", "k").usMarketHolidays();

        assertEquals(2, list.size());
        assertTrue(list.get(0).fullDayClosed());
        assertFalse(list.get(1).fullDayClosed()); // 조기 폐장은 거래일
        assertEquals("k", http.headers.get(0).get("X-Finnhub-Token"));
    }

    @Test
    void koreaEximStripsThousandsSeparator() {
        FakeHttp http = new FakeHttp().on("exchangeJSON", """
                [{"result":1,"cur_unit":"JPY(100)","deal_bas_r":"912.3"},
                 {"result":1,"cur_unit":"USD","ttb":"1,371.62","tts":"1,399.33","deal_bas_r":"1,385.5","cur_nm":"미국 달러"}]""");
        var q = new KoreaEximClient(http, "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON", "k")
                .usdKrw(LocalDate.of(2026, 10, 26));

        assertTrue(q.isPresent());
        assertEquals(new BigDecimal("1385.5"), q.get().rate());
        assertEquals(LocalDate.of(2026, 10, 26), q.get().date());
        assertTrue(http.requests.get(0).toString().contains("searchdate=20261026"));
    }

    @Test
    void koreaEximEmptyMeansNotPublished() {
        FakeHttp http = new FakeHttp().on("exchangeJSON", "[]");
        assertTrue(new KoreaEximClient(http, "https://x/exchangeJSON", "k").usdKrw(LocalDate.of(2026, 10, 25)).isEmpty());
    }

    @Test
    void koreaEximAuthErrorIsReported() {
        FakeHttp http = new FakeHttp().on("exchangeJSON", "[{\"result\":3}]");
        FetchException e = assertThrows(FetchException.class,
                () -> new KoreaEximClient(http, "https://x/exchangeJSON", "k").usdKrw(LocalDate.of(2026, 10, 26)));
        assertTrue(e.getMessage().contains("result=3"), e.getMessage());
    }

    @Test
    void frankfurterV2Rate() {
        FakeHttp http = new FakeHttp().on("/v2/rate/USD/KRW",
                "{\"date\":\"2026-10-26\",\"base\":\"USD\",\"quote\":\"KRW\",\"rate\":1385.2}");
        var q = new FrankfurterClient(http, "https://api.frankfurter.dev").usdKrw();

        assertEquals(new BigDecimal("1385.2"), q.rate());
        assertEquals(FrankfurterClient.SOURCE, q.source());
    }
}

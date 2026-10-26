package com.myrobo.collector;

import com.myrobo.collector.CollectorTypes.RefreshMode;
import com.myrobo.collector.CollectorTypes.SymbolResult;
import com.myrobo.collector.Fakes.FakeHttp;
import com.myrobo.collector.Fakes.MemoryFxStore;
import com.myrobo.collector.Fakes.MemoryHolidayStore;
import com.myrobo.collector.Fakes.MemoryPriceStore;
import com.myrobo.collector.Fakes.MutableClock;
import com.myrobo.collector.http.RateLimiter;
import com.myrobo.collector.http.RateLimiter.LimitExceededException;
import com.myrobo.collector.source.FinnhubClient;
import com.myrobo.collector.source.FrankfurterClient;
import com.myrobo.collector.source.KoreaEximClient;
import com.myrobo.collector.source.TiingoClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 수집 흐름: 거래일 계산, 증분·전체 재수집 판단, 호출 한도, 환율 예비 경로, 실패 격리. */
class CollectorFlowTest {

    private static final LocalDate BACKFILL = LocalDate.of(2000, 1, 1);

    // ── 거래일 ──

    private static Instant kst(String localDateTime) {
        return LocalDate.parse(localDateTime.substring(0, 10))
                .atTime(java.time.LocalTime.parse(localDateTime.substring(11)))
                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }

    @Test
    void sessionAt8amKstSummerAndWinter() {
        MarketCalendar cal = new MarketCalendar(Set.of());
        // 서머타임 중: 수 08:00 KST = 화 19:00 EDT → 화요일 장
        assertEquals(LocalDate.of(2026, 10, 27), cal.lastCompletedSession(kst("2026-10-28T08:00")));
        // 서머타임 해제 후(11/1): 화 08:00 KST = 월 18:00 EST → 월요일 장
        assertEquals(LocalDate.of(2026, 11, 30), cal.lastCompletedSession(kst("2026-12-01T08:00")));
        // 07:00 KST(겨울) = 17:00 EST → 일봉 준비 전이라 하루 전 장. 수집을 08:00에 하는 이유
        assertEquals(LocalDate.of(2026, 11, 27), cal.lastCompletedSession(kst("2026-12-01T07:00")));
    }

    @Test
    void sessionSkipsWeekendAndHoliday() {
        MarketCalendar cal = new MarketCalendar(Set.of(LocalDate.of(2026, 11, 26))); // 추수감사절
        // 월 08:00 KST = 일 19:00 EST → 금요일 장
        assertEquals(LocalDate.of(2026, 11, 20), cal.lastCompletedSession(kst("2026-11-23T08:00")));
        // 금 08:00 KST = 목(휴장) 18:00 EST → 수요일 장
        assertEquals(LocalDate.of(2026, 11, 25), cal.lastCompletedSession(kst("2026-11-27T08:00")));
    }

    // ── 호출 한도 ──

    @Test
    void rateLimiterWaitsForHourlyWindowAndStopsAtDailyCap() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        RateLimiter limiter = new RateLimiter("t", 2, 3, clock, clock);

        limiter.acquire();
        clock.advance(Duration.ofMinutes(10));
        limiter.acquire();
        assertTrue(clock.slept.isEmpty());

        limiter.acquire(); // 시간당 2회 초과 → 0분에 한 첫 호출이 1시간 창을 벗어날 때(60분)까지, 즉 50분 대기
        assertEquals(java.util.List.of(Duration.ofMinutes(50).plusSeconds(1)), clock.slept);

        assertThrows(LimitExceededException.class, limiter::acquire); // 하루 3회 초과
    }

    // ── 증분 / 전체 재수집 ──

    private PriceCollector collector(FakeHttp http, MemoryPriceStore store) {
        MutableClock c = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        return new PriceCollector(new TiingoClient(http, new RateLimiter("t", 45, 900, c, c),
                "https://api.tiingo.com", "k"), store, BACKFILL);
    }

    @Test
    void emptyStoreBackfillsFromStart() {
        FakeHttp http = new FakeHttp().on("/prices", Fakes.array(
                Fakes.tiingoBar("2026-10-26", "375.1", "375.1", "0.0", "1.0"),
                Fakes.tiingoBar("2026-10-27", "375.9", "375.9", "0.0", "1.0")));
        MemoryPriceStore store = new MemoryPriceStore();

        SymbolResult r = collector(http, store).refresh("VTI", LocalDate.of(2026, 10, 27));

        assertEquals(RefreshMode.BACKFILL, r.mode());
        assertTrue(http.requests.get(0).toString().contains("startDate=2000-01-01"));
        assertEquals(2, store.bars.get("VTI").size());
    }

    @Test
    void upToDateMakesNoCall() {
        FakeHttp http = new FakeHttp();
        MemoryPriceStore store = new MemoryPriceStore();
        store.saveBars(java.util.List.of(new CollectorTypes.DailyBar("VTI", LocalDate.of(2026, 10, 27),
                null, null, null, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, 1L,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE)));

        SymbolResult r = collector(http, store).refresh("VTI", LocalDate.of(2026, 10, 27));

        assertEquals(RefreshMode.UP_TO_DATE, r.mode());
        assertEquals(0, http.requests.size());
    }

    @Test
    void normalDayFetchesOnlyNewDays() {
        MemoryPriceStore store = new MemoryPriceStore();
        FakeHttp first = new FakeHttp().on("/prices", Fakes.array(Fakes.tiingoBar("2026-10-26", "375", "375", "0.0", "1.0")));
        collector(first, store).refresh("VTI", LocalDate.of(2026, 10, 26));

        FakeHttp http = new FakeHttp().on("/prices", Fakes.array(Fakes.tiingoBar("2026-10-27", "376", "376", "0.0", "1.0")));
        SymbolResult r = collector(http, store).refresh("VTI", LocalDate.of(2026, 10, 27));

        assertEquals(RefreshMode.INCREMENTAL, r.mode());
        assertEquals(1, http.requests.size());
        assertTrue(http.requests.get(0).toString().contains("startDate=2026-10-27"));
    }

    @Test
    void dividendDayRefetchesWholeAdjustedHistory() {
        MemoryPriceStore store = new MemoryPriceStore();
        collector(new FakeHttp().on("/prices", Fakes.array(
                Fakes.tiingoBar("2026-10-26", "100", "100", "0.0", "1.0"))), store)
                .refresh("BIL", LocalDate.of(2026, 10, 26));

        // 새로 받은 날에 배당락 → 과거 수정주가가 바뀌었으므로 전체를 다시 받는다
        FakeHttp http = new FakeHttp()
                .on("startDate=2026-10-27", Fakes.array(Fakes.tiingoBar("2026-10-27", "99.6", "99.6", "0.4", "1.0")))
                .on("startDate=2000-01-01", Fakes.array(
                        Fakes.tiingoBar("2026-10-26", "100", "99.6", "0.0", "1.0"),
                        Fakes.tiingoBar("2026-10-27", "99.6", "99.6", "0.4", "1.0")));
        SymbolResult r = collector(http, store).refresh("BIL", LocalDate.of(2026, 10, 27));

        assertEquals(RefreshMode.REFETCH_ADJUSTED, r.mode());
        assertEquals(2, http.requests.size());
        // 어제 수정주가가 새 값으로 덮어써졌는지
        assertEquals(new java.math.BigDecimal("99.6"), store.bars.get("BIL").get(LocalDate.of(2026, 10, 26)).adjClose());
        assertEquals(1, store.actions.size());
    }

    // ── 전체 흐름 ──

    private static final String HOLIDAYS = """
            {"data":[{"eventName":"Thanksgiving","atDate":"2026-11-26","tradingHour":""}],"exchange":"US"}""";
    private static final String FRANKFURTER = "{\"date\":\"2026-10-27\",\"base\":\"USD\",\"quote\":\"KRW\",\"rate\":1390.1}";

    private record Rig(CollectorService service, FakeHttp http, MemoryPriceStore prices,
                       MemoryHolidayStore holidays, MemoryFxStore fx) {
    }

    private Rig rig(FakeHttp http, String eximKey, int perDay) {
        MutableClock c = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        MemoryPriceStore prices = new MemoryPriceStore();
        MemoryHolidayStore holidays = new MemoryHolidayStore();
        MemoryFxStore fx = new MemoryFxStore();
        TiingoClient tiingo = new TiingoClient(http, new RateLimiter("t", 45, perDay, c, c), "https://api.tiingo.com", "k");
        CollectorService s = new CollectorService(
                new FinnhubClient(http, "https://finnhub.io/api/v1", "k"), holidays,
                new PriceCollector(tiingo, prices, BACKFILL),
                new FxResolver(new KoreaEximClient(http, "https://oapi.koreaexim.go.kr/x/exchangeJSON", eximKey),
                        new FrankfurterClient(http, "https://api.frankfurter.dev")),
                fx);
        return new Rig(s, http, prices, holidays, fx);
    }

    private static final TreeSet<String> CORE = new TreeSet<>(Set.of("BIL", "GLDM", "SCHR", "VTI"));

    @Test
    void oneSymbolFailingDoesNotStopOthers() {
        FakeHttp http = new FakeHttp()
                .on("/stock/market-holiday", HOLIDAYS)
                .onFail("/tiingo/daily/gldm/", 404)
                .on("/tiingo/daily/", Fakes.array(Fakes.tiingoBar("2026-10-27", "10", "10", "0.0", "1.0")))
                .on("/v2/rate/USD/KRW", FRANKFURTER);
        Rig rig = rig(http, "", 900);

        var result = rig.service().runDaily(kst("2026-10-28T08:00"), CORE);

        assertEquals(LocalDate.of(2026, 10, 27), result.session());
        assertEquals(RefreshMode.FAILED, result.prices().stream().filter(p -> p.symbol().equals("GLDM")).findFirst().orElseThrow().mode());
        assertEquals(3, rig.prices().bars.size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("GLDM"), result.errors().toString());
        assertEquals(1, rig.fx().saved.size()); // 환율은 그대로 진행
    }

    @Test
    void holidayFetchFailureFallsBackToStoredHolidays() {
        FakeHttp http = new FakeHttp()
                .onFail("/stock/market-holiday", 503)
                .on("/tiingo/daily/", Fakes.array(Fakes.tiingoBar("2026-11-25", "10", "10", "0.0", "1.0")))
                .on("/v2/rate/USD/KRW", FRANKFURTER);
        Rig rig = rig(http, "", 900);
        rig.holidays().closed.add(LocalDate.of(2026, 11, 26)); // 예전에 저장해 둔 추수감사절

        var result = rig.service().runDaily(kst("2026-11-27T08:00"), new TreeSet<>(Set.of("VTI")));

        assertEquals(LocalDate.of(2026, 11, 25), result.session());
        assertTrue(result.ok(), result.errors().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("휴장일 갱신 실패")), result.warnings().toString());
    }

    @Test
    void eximLooksBackPastWeekendThenFallsBackOnlyIfNeeded() {
        FakeHttp http = new FakeHttp()
                .on("/stock/market-holiday", HOLIDAYS)
                .on("searchdate=20261026", "[]")   // 월요일: 아직 고시 전
                .on("searchdate=20261023", "[{\"result\":1,\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,382.0\"}]") // 금요일
                .on("/v2/rate/USD/KRW", FRANKFURTER);
        Rig rig = rig(http, "exim-key", 900);

        var result = rig.service().runDaily(kst("2026-10-26T08:00"), new TreeSet<>());

        assertEquals(KoreaEximClient.SOURCE, result.fx().source());
        assertEquals(LocalDate.of(2026, 10, 23), result.fx().date());
        assertEquals(0, http.count("searchdate=20261025") + http.count("searchdate=20261024")); // 주말은 조회 안 함
        assertEquals(0, http.count("frankfurter"));
    }

    @Test
    void missingEximKeyUsesFrankfurterWithWarning() {
        FakeHttp http = new FakeHttp().on("/stock/market-holiday", HOLIDAYS).on("/v2/rate/USD/KRW", FRANKFURTER);
        Rig rig = rig(http, "", 900);

        var result = rig.service().runDaily(kst("2026-10-28T08:00"), new TreeSet<>());

        assertEquals(FrankfurterClient.SOURCE, result.fx().source());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("KOREAEXIM_KEY")), result.warnings().toString());
        assertEquals(0, http.count("exchangeJSON"));
    }

    @Test
    void dailyCapSkipsRemainingSymbols() {
        FakeHttp http = new FakeHttp()
                .on("/stock/market-holiday", HOLIDAYS)
                .on("/tiingo/daily/", Fakes.array(Fakes.tiingoBar("2026-10-27", "10", "10", "0.0", "1.0")))
                .on("/v2/rate/USD/KRW", FRANKFURTER);
        Rig rig = rig(http, "", 2); // 하루 2회만 허용

        var result = rig.service().runDaily(kst("2026-10-28T08:00"), CORE);

        assertEquals(2, rig.prices().bars.size());
        assertEquals(2, result.prices().stream().filter(p -> p.mode() == RefreshMode.SKIPPED).count());
        assertTrue(!result.ok() && result.errors().get(0).contains("하루 호출 한도"), result.errors().toString());
    }
}

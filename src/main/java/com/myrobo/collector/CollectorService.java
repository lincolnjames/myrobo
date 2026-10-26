package com.myrobo.collector;

import com.myrobo.collector.CollectorStores.FxStore;
import com.myrobo.collector.CollectorStores.HolidayStore;
import com.myrobo.collector.CollectorTypes.CollectResult;
import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.CollectorTypes.RefreshMode;
import com.myrobo.collector.CollectorTypes.SymbolResult;
import com.myrobo.collector.http.RateLimiter.LimitExceededException;
import com.myrobo.collector.source.FinnhubClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 하루 한 번 수집: 휴장일 → 최근 거래일 계산 → 종목별 일봉 → 환율.
 * 한 종목이 실패해도 나머지는 계속하고, 실패는 결과에 모아 알린다 (조용히 넘어가지 않는다).
 * 하루 호출 한도에 닿으면 남은 종목은 SKIPPED로 두고 다음 실행에 받는다.
 */
public final class CollectorService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final FinnhubClient finnhub;
    private final HolidayStore holidays;
    private final PriceCollector prices;
    private final FxResolver fx;
    private final FxStore fxStore;

    public CollectorService(FinnhubClient finnhub, HolidayStore holidays, PriceCollector prices,
                            FxResolver fx, FxStore fxStore) {
        this.finnhub = finnhub;
        this.holidays = holidays;
        this.prices = prices;
        this.fx = fx;
        this.fxStore = fxStore;
    }

    public CollectResult runDaily(Instant now, SortedSet<String> symbols) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 1. 휴장일: 받기 실패해도 이미 저장된 휴장일로 계속한다
        int holidayCount = 0;
        try {
            var list = finnhub.usMarketHolidays();
            holidays.save(list);
            holidayCount = list.size();
        } catch (RuntimeException e) {
            warnings.add("휴장일 갱신 실패, 저장된 휴장일 사용: " + e.getMessage());
        }
        MarketCalendar calendar = new MarketCalendar(holidays.fullDayClosures());
        LocalDate session = calendar.lastCompletedSession(now);

        // 2. 일봉
        List<SymbolResult> results = new ArrayList<>();
        boolean limitHit = false;
        for (String symbol : new TreeSet<>(symbols)) {
            if (limitHit) {
                results.add(new SymbolResult(symbol, RefreshMode.SKIPPED, 0, null, "호출 한도로 다음 실행에 수집"));
                continue;
            }
            try {
                SymbolResult r = prices.refresh(symbol, session);
                results.add(r);
                if (r.message() != null) {
                    warnings.add(symbol + ": " + r.message());
                }
            } catch (LimitExceededException e) {
                limitHit = true;
                results.add(new SymbolResult(symbol, RefreshMode.SKIPPED, 0, null, e.getMessage()));
                errors.add(e.getMessage());
            } catch (RuntimeException e) {
                results.add(new SymbolResult(symbol, RefreshMode.FAILED, 0, null, e.getMessage()));
                errors.add(symbol + " 일봉 수집 실패: " + e.getMessage());
            }
        }

        // 3. 환율
        FxQuote quote = null;
        try {
            quote = fx.resolve(now.atZone(SEOUL).toLocalDate(), warnings);
            fxStore.save(quote);
        } catch (RuntimeException e) {
            errors.add("환율 수집 실패: " + e.getMessage());
        }

        return new CollectResult(session, List.copyOf(results), quote, holidayCount,
                List.copyOf(warnings), List.copyOf(errors));
    }
}

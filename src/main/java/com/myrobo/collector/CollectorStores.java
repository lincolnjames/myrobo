package com.myrobo.collector;

import com.myrobo.collector.CollectorTypes.DailyBar;
import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.CollectorTypes.MarketHoliday;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 수집 결과를 저장하는 곳. 운영에서는 Postgres(JDBC) 구현, 테스트에서는 메모리 구현을 쓴다.
 * 모든 저장은 upsert라 같은 데이터를 다시 받아도 중복이 생기지 않는다.
 */
public final class CollectorStores {

    private CollectorStores() {
    }

    public interface PriceStore {
        Optional<LocalDate> lastDate(String symbol);

        /** 같은 (종목, 날짜)가 있으면 덮어쓴다. 수정주가가 다시 계산된 경우를 위해서다. */
        void saveBars(List<DailyBar> bars);

        /** 분할·배당이 있는 날만 기록한다. */
        void saveCorporateActions(List<DailyBar> barsWithActions);
    }

    public interface HolidayStore {
        void save(List<MarketHoliday> holidays);

        /** 종일 휴장일 전체. 달력을 만들 때 쓴다. */
        Set<LocalDate> fullDayClosures();
    }

    public interface FxStore {
        void save(FxQuote quote);
    }
}

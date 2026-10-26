package com.myrobo.collector;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * 미국 거래일 달력. 날짜 판단은 항상 뉴욕 시간 기준이라 서머타임 전환에 영향받지 않는다.
 * 종일 휴장일만 휴장으로 보고, 조기 폐장일은 거래일로 본다.
 */
public final class MarketCalendar {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /**
     * 장 마감(16:00) 뒤 일봉이 데이터 제공처에 반영되기까지 여유를 둔 시각.
     * 이 시각 전이면 오늘 장은 아직 "끝난 거래일"로 치지 않는다.
     */
    public static final LocalTime EOD_READY = LocalTime.of(17, 45);

    private final Set<LocalDate> closedDays;

    public MarketCalendar(Set<LocalDate> closedDays) {
        this.closedDays = Set.copyOf(closedDays);
    }

    public boolean isTradingDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !closedDays.contains(d);
    }

    public LocalDate previousTradingDay(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!isTradingDay(p)) {
            p = p.minusDays(1);
        }
        return p;
    }

    /** now 시점에 일봉이 나와 있어야 하는 가장 최근 거래일. */
    public LocalDate lastCompletedSession(Instant now) {
        ZonedDateTime ny = now.atZone(NEW_YORK);
        LocalDate today = ny.toLocalDate();
        if (isTradingDay(today) && !ny.toLocalTime().isBefore(EOD_READY)) {
            return today;
        }
        return previousTradingDay(today);
    }
}

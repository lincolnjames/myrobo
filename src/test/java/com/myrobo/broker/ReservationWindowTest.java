package com.myrobo.broker;

import com.myrobo.collector.MarketCalendar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 예약 접수 시간. 2026-11-02(월)은 미국 표준시(한국 = 뉴욕 + 14시간), 10-27(화)은 서머타임(+13시간). */
class ReservationWindowTest {

    private static final MarketCalendar CAL = new MarketCalendar(Set.of());
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate WED = LocalDate.of(2026, 11, 4);

    private static ReservationWindow.Decision at(String utc, LocalDate trade, LocalDate expires) {
        return ReservationWindow.check(Instant.parse(utc), CAL, trade, expires);
    }

    @Test
    void openAtNoonOnTheTradeDate() {
        var d = at("2026-11-02T03:00:00Z", MON, WED); // KST 12:00

        assertTrue(d.open(), d.reason());
        assertEquals(MON, d.session());
    }

    @Test
    void closedBeforeTenInTheMorning() {
        assertFalse(at("2026-11-02T01:00:00Z", MON, WED).open()); // KST 10:00 (10:05부터)
        assertTrue(at("2026-11-02T01:06:00Z", MON, WED).open());  // KST 10:06
    }

    @Test
    void closedDuringSettlement() {
        assertFalse(at("2026-11-02T07:30:00Z", MON, WED).open()); // KST 16:30
        assertTrue(at("2026-11-02T07:55:00Z", MON, WED).open());  // KST 16:55
    }

    @Test
    void cutoffFollowsNewYorkTimeInWinter() {
        assertTrue(at("2026-11-02T14:05:00Z", MON, WED).open());  // KST 23:05 = 뉴욕 09:05
        assertFalse(at("2026-11-02T14:10:00Z", MON, WED).open()); // KST 23:10 = 뉴욕 09:10
    }

    @Test
    void cutoffFollowsNewYorkTimeInSummer() {
        LocalDate tue = LocalDate.of(2026, 10, 27);
        LocalDate thu = LocalDate.of(2026, 10, 29);

        assertTrue(at("2026-10-27T13:05:00Z", tue, thu).open());  // KST 22:05 = 뉴욕 09:05 (EDT)
        assertFalse(at("2026-10-27T13:15:00Z", tue, thu).open()); // KST 22:15 = 뉴욕 09:15
    }

    @Test
    void closedOnWeekendsAndHolidays() {
        assertFalse(at("2026-11-07T03:00:00Z", MON, LocalDate.of(2026, 11, 9)).open()); // 토요일
        var thanksgiving = new MarketCalendar(Set.of(LocalDate.of(2026, 11, 26)));
        assertFalse(ReservationWindow.check(Instant.parse("2026-11-26T03:00:00Z"), thanksgiving,
                LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 30)).open());
    }

    @Test
    void onlyWithinTheTradeDateAndExpiry() {
        assertFalse(at("2026-11-02T03:00:00Z", LocalDate.of(2026, 11, 3), WED).open()); // 주문일 전
        assertTrue(at("2026-11-03T03:00:00Z", MON, WED).open());                         // 유효기한 안
        assertFalse(at("2026-11-05T03:00:00Z", MON, WED).open());                        // 유효기한 뒤
    }
}

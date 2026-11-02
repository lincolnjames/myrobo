package com.myrobo.broker;

import com.myrobo.collector.MarketCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 지금 미국 예약주문을 넣을 수 있는지. 순수 계산.
 *
 * <p>KIS 미국 예약주문 접수 시간(공식 예제 order_resv.py 문서): 한국 시간 10:00 ~ 23:20 (서머타임 22:20),
 * 16:30 ~ 16:45경은 정산으로 제한, 접수분은 23:30(서머타임 22:30) 정규장에 전송, 유효기간 당일.
 * 마감 23:20/22:20은 뉴욕 시간으로 늘 09:20이므로 뉴욕 시간으로 잰다. 경계에 여유를 둔다.
 *
 * <p>한국 날짜 D의 밤에 열리는 장은 뉴욕 날짜 D다. 그 장이 주문안의 주문일~유효기한 안일 때만 넣는다.
 */
public final class ReservationWindow {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalTime OPENS_KST = LocalTime.of(10, 5);
    static final LocalTime BLACKOUT_FROM_KST = LocalTime.of(16, 25);
    static final LocalTime BLACKOUT_TO_KST = LocalTime.of(16, 50);
    static final LocalTime CUTOFF_NY = LocalTime.of(9, 10);

    public record Decision(boolean open, LocalDate session, String reason) {
    }

    private ReservationWindow() {
    }

    public static Decision check(Instant now, MarketCalendar cal, LocalDate tradeDate, LocalDate expiresAt) {
        ZonedDateTime kst = now.atZone(SEOUL);
        LocalDate session = kst.toLocalDate();
        if (!cal.isTradingDay(session)) {
            return closed(session, "오늘 밤(뉴욕 " + session + ")은 휴장");
        }
        if (session.isBefore(tradeDate)) {
            return closed(session, "주문일 " + tradeDate + " 전");
        }
        if (expiresAt != null && session.isAfter(expiresAt)) {
            return closed(session, "주문안 유효기한 " + expiresAt + "이 지남");
        }
        LocalTime t = kst.toLocalTime();
        if (t.isBefore(OPENS_KST)) {
            return closed(session, "예약 접수는 한국 시간 " + OPENS_KST + "부터");
        }
        if (!t.isBefore(BLACKOUT_FROM_KST) && t.isBefore(BLACKOUT_TO_KST)) {
            return closed(session, "16:30~16:45 정산 시간");
        }
        Instant cutoff = session.atTime(CUTOFF_NY).atZone(MarketCalendar.NEW_YORK).toInstant();
        if (!now.isBefore(cutoff)) {
            return closed(session, "오늘 예약 접수 마감 (뉴욕 " + CUTOFF_NY + ")");
        }
        return new Decision(true, session, "");
    }

    private static Decision closed(LocalDate session, String reason) {
        return new Decision(false, session, reason);
    }
}

package com.myrobo.plan;

import com.myrobo.collector.MarketCalendar;

import java.time.LocalDate;

/**
 * 오늘(한국 날짜) 기준 주문 일정. 순수 계산.
 *
 * <p>미국장은 한국 시간 밤에 열리므로, 한국 날짜 D에 만든 주문안은 뉴욕 거래일 D(휴장이면 그다음 거래일)
 * 장에서 넣는다고 본다. 그날 기준으로 시세가 있어야 하는 마지막 거래일은 그 전 거래일이다.
 *
 * @param tradeDate     주문을 넣을 미국 거래일
 * @param lastSession   시세가 나와 있어야 하는 가장 최근 거래일 (tradeDate의 전 거래일)
 * @param rebalanceDay  매월 첫 거래일 (쌓인 현금 투입일)
 * @param splitDay      매주 첫 거래일 (큰 주문안을 나눠 내는 날)
 * @param nextSplitDay  다음 분할 주문일 (tradeDate 이후)
 * @param expiresAt     지금 주문안을 만들면 유효한 마지막 거래일
 */
public record TradingSchedule(
        LocalDate tradeDate,
        LocalDate lastSession,
        boolean rebalanceDay,
        boolean splitDay,
        LocalDate nextSplitDay,
        LocalDate expiresAt) {

    public static TradingSchedule of(MarketCalendar cal, LocalDate todayKst, int expireTradingDays) {
        LocalDate trade = cal.onOrAfter(todayKst);
        LocalDate next = cal.nextTradingDay(trade);
        while (!cal.isFirstTradingDayOfWeek(next)) {
            next = cal.nextTradingDay(next);
        }
        return new TradingSchedule(trade, cal.previousTradingDay(trade),
                cal.isFirstTradingDayOfMonth(trade), cal.isFirstTradingDayOfWeek(trade), next,
                cal.plusTradingDays(trade, expireTradingDays - 1));
    }
}

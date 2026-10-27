package com.myrobo.ops;

import com.myrobo.plan.PlanViews.CheckView;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.Outcome;
import com.myrobo.valuation.Drawdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 알림 문구와 보낼지 말지의 구분. */
class NotificationMessagesTest {

    private static CreateResult of(Outcome o, String status, List<CheckView> checks) {
        return DailyJobTest.result(o, o == Outcome.NO_ACTION ? null : DailyJobTest.plan(status), checks);
    }

    private static final CheckView STARTED_FAIL = new CheckView("GOAL_STARTED", "목표 시작일", false, "시작일 전");
    private static final CheckView FRESH_FAIL = new CheckView("PRICE_FRESH", "시세 신선도", false, "3거래일 전");
    private static final CheckView OK = new CheckView("FX_FRESH", "환율 신선도", true, "1일 전");

    @Test
    void drawdownMessagesCarryPercentagesAndTheStepButNoAmounts() {
        var peak = LocalDate.of(2026, 11, 3);
        String alert = NotificationMessages.drawdown(new Drawdown.Alert(Drawdown.Alert.Kind.UP,
                new Drawdown.Level(1, new BigDecimal("15"), "ALERT"), new BigDecimal("16.2"), peak));
        String satellite = NotificationMessages.drawdown(new Drawdown.Alert(Drawdown.Alert.Kind.UP,
                new Drawdown.Level(2, new BigDecimal("20"), "STOP_SATELLITE_BUYS"), new BigDecimal("20.5"), peak));
        String limit = NotificationMessages.drawdown(new Drawdown.Alert(Drawdown.Alert.Kind.UP,
                new Drawdown.Level(3, new BigDecimal("25.0"), Drawdown.LIMIT), new BigDecimal("25"), peak));
        String recovered = NotificationMessages.drawdown(new Drawdown.Alert(Drawdown.Alert.Kind.RECOVERED,
                null, new BigDecimal("12.3456"), peak));

        assertTrue(alert.contains("고점(2026-11-03) 대비 16.20% 하락") && alert.contains("경보선 15%"), alert);
        assertTrue(satellite.contains("위성 매수 중단"), satellite);
        assertTrue(limit.contains("최대 낙폭 한도") && limit.contains("경보선 25%"), limit);
        assertTrue(recovered.contains("경보 해제") && recovered.contains("12.35%"), recovered);
        assertFalse(alert.contains("원") || alert.contains("$"), alert);
    }

    @Test
    void sentMessageListsSellFxBuyInOrderWithoutTotals() {
        String m = NotificationMessages.forPlan(of(Outcome.SENT, "SENT", List.of())).orElseThrow();

        int sell = m.indexOf("매도 BIL 10주");
        int fx = m.indexOf("환전");
        int buy = m.indexOf("매수 VTI 5주");
        assertTrue(sell > 0 && sell < fx && fx < buy, m); // 매도 → 환전 → 매수 (화면과 같은 순서)
        assertTrue(m.contains("2026-11-04까지 유효"), m);
        assertTrue(m.contains("체결 결과를 입력"), m);
        assertFalse(m.contains("5000"), "총 주문액 같은 합계는 외부로 보내지 않는다: " + m);
    }

    @Test
    void rejectedMessageNamesTheFailedChecksAndForbidsOrdering() {
        String m = NotificationMessages.forPlan(of(Outcome.REJECTED, "REJECTED", List.of(FRESH_FAIL, OK))).orElseThrow();

        assertTrue(m.contains("주문하지 마세요"), m);
        assertTrue(m.contains("시세 신선도"), m);
        assertFalse(m.contains("환율 신선도"), "통과한 규칙은 적지 않는다: " + m);
    }

    @Test
    void openPlanSendsAReminderWithTheDeadline() {
        String m = NotificationMessages.forPlan(of(Outcome.OPEN_EXISTS, "SENT", List.of())).orElseThrow();

        assertTrue(m.contains("미완료 주문안 #7"), m);
        assertTrue(m.contains("2026-11-04까지"), m);
    }

    @Test
    void noActionIsQuietUnlessADataCheckFailed() {
        assertEquals(Optional.empty(), NotificationMessages.forPlan(of(Outcome.NO_ACTION, null, List.of(OK))));
        // 시작일 전이라는 이유는 데이터 문제가 아니다
        assertEquals(Optional.empty(), NotificationMessages.forPlan(of(Outcome.NO_ACTION, null, List.of(STARTED_FAIL))));

        String m = NotificationMessages.forPlan(of(Outcome.NO_ACTION, null, List.of(STARTED_FAIL, FRESH_FAIL)))
                .orElseThrow();
        assertTrue(m.contains("믿을 수 없어요"), m);
        assertTrue(m.contains("시세 신선도"), m);
        assertFalse(m.contains("목표 시작일"), "시작일 전은 원인으로 적지 않는다: " + m);
    }

    @Test
    void alreadyTodayIsQuiet() {
        assertEquals(Optional.empty(), NotificationMessages.forPlan(of(Outcome.ALREADY_TODAY, "SENT", List.of())));
    }

    @Test
    void longErrorListsAreCapped() {
        List<String> errors = java.util.stream.IntStream.range(0, 25).mapToObj(i -> "오류 " + i).toList();

        String m = NotificationMessages.collectionErrors(errors);

        assertTrue(m.contains("오류 9") && !m.contains("오류 10"), m);
        assertTrue(m.contains("외 15건"), m);
    }

    @Test
    void failureMessageKeepsTheReasonButCapsItsLength() {
        String m = NotificationMessages.failure("수집", new IllegalStateException("x".repeat(1000)));

        assertTrue(m.contains("IllegalStateException"), m);
        assertTrue(m.length() < 400, "길이 " + m.length());
    }
}

package com.myrobo.valuation;

import com.myrobo.valuation.Drawdown.Alert;
import com.myrobo.valuation.Drawdown.Level;
import com.myrobo.valuation.Drawdown.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 지수 이어 붙이기, 고점·낙폭, 경보 단계(되돌림 여유 포함)와 알릴지 여부. */
class DrawdownTest {

    private static final List<Level> LEVELS = List.of(
            new Level(1, new BigDecimal("15"), "ALERT"),
            new Level(2, new BigDecimal("20"), "STOP_SATELLITE_BUYS"),
            new Level(3, new BigDecimal("25"), Drawdown.LIMIT));
    private static final LocalDate D0 = LocalDate.of(2026, 11, 2);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** 수익률(퍼센트)을 차례로 적용한 마지막 기록. */
    private static Row walk(String... returnsPct) {
        Row row = Drawdown.next(null, D0, d("1000"), null, LEVELS);
        for (int i = 0; i < returnsPct.length; i++) {
            row = Drawdown.next(row, D0.plusDays(i + 1), d("1000"),
                    d(returnsPct[i]).movePointLeft(2), LEVELS);
        }
        return row;
    }

    @Test
    void theFirstRowStartsAtOneHundredWithNoReturn() {
        Row first = Drawdown.next(null, D0, d("1234.567"), null, LEVELS);

        assertEquals(0, Drawdown.START_NAV.compareTo(first.nav()));
        assertEquals(D0, first.peakDate());
        assertNull(first.returnPct());
        assertEquals(0, first.drawdownPct().signum());
        assertEquals(0, first.level());
        assertEquals(d("1234.57"), first.totalKrw());
    }

    @Test
    void newHighsMoveThePeakAndFallsMeasureFromIt() {
        Row up = walk("10");
        Row down = walk("10", "-10");

        assertEquals(0, d("110").compareTo(up.peakNav()));
        assertEquals(D0.plusDays(1), up.peakDate());
        assertEquals(0, d("99").compareTo(down.nav()));
        assertEquals(D0.plusDays(1), down.peakDate()); // 고점은 그대로
        assertEquals(d("10.0000"), down.drawdownPct());
        assertEquals(d("-10.000000"), down.returnPct());
    }

    @Test
    void crossingLinesRaisesTheLevelImmediately() {
        assertEquals(0, walk("-14.99").level());
        assertEquals(1, walk("-15").level());
        assertEquals(2, walk("-21").level());
        assertEquals(3, walk("-30").level());
    }

    @Test
    void theLevelDropsOnlyAfterRecoveringPastTheMargin() {
        // 고점 대비 -16% → 단계 1. 그다음 -14%까지 회복해도(경보선 15 - 여유 2 = 13 미만이 아니므로) 단계 유지
        Row at16 = walk("-16");
        Row at14 = Drawdown.next(at16, D0.plusDays(2), d("1000"), d("86").divide(d("84"), java.math.MathContext.DECIMAL128).subtract(BigDecimal.ONE), LEVELS);
        Row at12 = Drawdown.next(at14, D0.plusDays(3), d("1000"), d("88").divide(d("86"), java.math.MathContext.DECIMAL128).subtract(BigDecimal.ONE), LEVELS);

        assertEquals(1, at16.level());
        assertEquals(d("14.0000"), at14.drawdownPct());
        assertEquals(1, at14.level());
        assertEquals(d("12.0000"), at12.drawdownPct());
        assertEquals(0, at12.level());
    }

    @Test
    void levelForSkipsDownSeveralStepsWhenFarBelow() {
        assertEquals(0, Drawdown.levelFor(d("5"), LEVELS, 3));
        assertEquals(2, Drawdown.levelFor(d("22"), LEVELS, 3)); // 25 - 2 = 23 미만 → 원래 단계 2로
        assertEquals(3, Drawdown.levelFor(d("23.5"), LEVELS, 3));
    }

    @Test
    void levelForHandlesAShorterLevelListAfterAConfigChange() {
        assertEquals(1, Drawdown.levelFor(d("16"), LEVELS.subList(0, 1), 3));
    }

    @Test
    void alertsOnlyWhenTheLevelGoesUpOrBackToZero() {
        Row at16 = walk("-16");
        Row at21 = walk("-21");
        Row calm = walk("1");

        Alert up = Drawdown.alert(0, at16, LEVELS).orElseThrow();
        assertEquals(Alert.Kind.UP, up.kind());
        assertEquals("ALERT", up.level().action());
        assertEquals(2, Drawdown.alert(1, at21, LEVELS).orElseThrow().level().level());
        assertTrue(Drawdown.alert(1, at16, LEVELS).isEmpty());           // 같은 단계: 다시 알리지 않는다
        assertTrue(Drawdown.alert(2, at16, LEVELS).isEmpty());           // 내려갔지만 0은 아님: 조용히
        assertEquals(Alert.Kind.RECOVERED, Drawdown.alert(1, calm, LEVELS).orElseThrow().kind());
        assertTrue(Drawdown.alert(0, calm, LEVELS).isEmpty());
    }
}

package com.myrobo.valuation;

import com.myrobo.portfolio.Valuation.Price;
import com.myrobo.valuation.PeriodReturn.Action;
import com.myrobo.valuation.PeriodReturn.Holdings;
import com.myrobo.valuation.PeriodReturn.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 평가액과 기간 수익률: 입출금·매매가 끼어도, 분할·배당이 있어도 가격 변화만 잡는지. */
class PeriodReturnTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 3);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Market market(LocalDate date, String vti, String fx) {
        return new Market(Map.of("VTI", new Price(d(vti), date)), d(fx));
    }

    private static String pct(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    @Test
    void valueIsHoldingsAndUsdCashAtTheRatePlusKrwCash() {
        var h = new Holdings(Map.of("VTI", 10L), d("1000000"), d("100"));

        // (10 × 300 + 100) × 1400 + 1,000,000
        assertEquals(0, d("5340000").compareTo(PeriodReturn.valueKrw(h, market(D1, "300", "1400"))));
    }

    @Test
    void returnIsOnlyThePriceAndFxMoveOfWhatWasHeld() {
        var before = new Holdings(Map.of("VTI", 10L), BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal r = PeriodReturn.of(before, market(D1, "300", "1400"), market(D2, "330", "1400"), List.of());

        assertEquals("10.0000", pct(r));
    }

    @Test
    void fxMovesCountForUsdHoldingsButNotForKrwCash() {
        var before = new Holdings(Map.of(), d("1000000"), d("1000"));

        // 원화 100만 + 달러 1000 × 1400 = 240만 → 원화 100만 + 1000 × 1260 = 226만
        BigDecimal r = PeriodReturn.of(before, market(D1, "300", "1400"), market(D2, "300", "1260"), List.of());

        assertEquals("-5.8333", pct(r));
    }

    @Test
    void aSplitIsNotALoss() {
        var before = new Holdings(Map.of("VTI", 10L), BigDecimal.ZERO, BigDecimal.ZERO);
        var split = new Action("VTI", D2, "SPLIT", d("2"), null);

        BigDecimal r = PeriodReturn.of(before, market(D1, "300", "1400"), market(D2, "150", "1400"), List.of(split));

        assertEquals("0.0000", pct(r));
    }

    @Test
    void theExDividendDropIsNotALoss() {
        var before = new Holdings(Map.of("VTI", 10L), BigDecimal.ZERO, BigDecimal.ZERO);
        var dividend = new Action("VTI", D2, "DIVIDEND", null, d("3"));

        BigDecimal r = PeriodReturn.of(before, market(D1, "300", "1400"), market(D2, "297", "1400"), List.of(dividend));

        assertEquals("0.0000", pct(r));
    }

    @Test
    void actionsOutsideTheWindowOrForOtherSymbolsAreIgnored() {
        var before = new Holdings(Map.of("VTI", 10L), BigDecimal.ZERO, BigDecimal.ZERO);
        var old = new Action("VTI", D1, "SPLIT", d("2"), null);           // 앞 종가 날짜 당일 = 이미 반영됨
        var other = new Action("BIL", D2, "DIVIDEND", null, d("50"));

        BigDecimal r = PeriodReturn.of(before, market(D1, "300", "1400"), market(D2, "300", "1400"), List.of(old, other));

        assertEquals("0.0000", pct(r));
    }

    @Test
    void samePriceDateMeansNoChangeEvenWithAnActionListed() {
        var before = new Holdings(Map.of("VTI", 10L), BigDecimal.ZERO, BigDecimal.ZERO);
        var split = new Action("VTI", D2, "SPLIT", d("2"), null);

        // 주말: 두 시점 모두 같은 종가(같은 날짜)를 본다
        BigDecimal r = PeriodReturn.of(before, market(D1, "300", "1400"), market(D1, "300", "1400"), List.of(split));

        assertEquals("0.0000", pct(r));
    }

    @Test
    void emptyStartingPortfolioGivesZeroReturn() {
        BigDecimal r = PeriodReturn.of(PeriodReturn.Holdings.EMPTY, market(D1, "300", "1400"),
                market(D2, "330", "1400"), List.of());

        assertEquals(0, r.signum());
    }

    @Test
    void missingPriceOrFxIsReportedByName() {
        var h = new Holdings(Map.of("VTI", 10L, "GLDM", 5L), BigDecimal.ZERO, BigDecimal.ZERO);

        var noPrice = assertThrows(PeriodReturn.MissingData.class,
                () -> PeriodReturn.valueKrw(h, market(D1, "300", "1400")));
        var noFx = assertThrows(PeriodReturn.MissingData.class,
                () -> PeriodReturn.valueKrw(new Holdings(Map.of(), BigDecimal.ZERO, d("10")),
                        new Market(Map.of(), null)));

        assertTrue(noPrice.getMessage().contains("GLDM"), noPrice.getMessage());
        assertTrue(noFx.getMessage().contains("환율"), noFx.getMessage());
    }

    @Test
    void krwOnlyCashNeedsNoFx() {
        var h = new Holdings(Map.of(), d("500000"), BigDecimal.ZERO);

        assertEquals(0, d("500000").compareTo(PeriodReturn.valueKrw(h, new Market(Map.of(), null))));
    }

    @Test
    void zeroQuantityPositionsNeedNoPrice() {
        var h = new Holdings(Map.of("OLD", 0L), d("1"), BigDecimal.ZERO);

        assertEquals(0, BigDecimal.ONE.compareTo(PeriodReturn.valueKrw(h, new Market(Map.of(), null))));
    }
}

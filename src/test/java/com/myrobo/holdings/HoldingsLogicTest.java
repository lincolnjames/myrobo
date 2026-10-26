package com.myrobo.holdings;

import com.myrobo.holdings.SnapshotInput.Line;
import com.myrobo.portfolio.PlanTypes.PlannerConfig;
import com.myrobo.portfolio.Valuation;
import com.myrobo.portfolio.Valuation.Position;
import com.myrobo.portfolio.Valuation.Price;
import com.myrobo.web.LocalRequestGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 잔고 입력 검증, 현재 비중 평가, 로컬 요청 보호. */
class HoldingsLogicTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final Set<String> CORE = Set.of("VTI", "SCHR", "GLDM", "BIL");
    // 기한 없는 프로필: 위성 10%는 모의 운용이라 코어 비중대로 나눠진다
    private static final Map<String, BigDecimal> OPEN = Map.of(
            "VTI", new BigDecimal("0.35"), "SATELLITE", new BigDecimal("0.10"),
            "SCHR", new BigDecimal("0.30"), "GLDM", new BigDecimal("0.10"), "BIL", new BigDecimal("0.15"));

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    // ── 입력 검증 ──

    @Test
    void cleansSymbolsAndDropsZeroQuantityLines() {
        var r = new SnapshotInput(null, List.of(
                new Line(" vti ", 18L, d("360.12")),
                new Line("SCHR", 0L, null),           // 0주는 저장하지 않음
                new Line("", null, null),             // 빈 줄은 무시
                new Line("005930", 10L, null)),       // 한국 종목코드도 기록 가능 (계산에서는 제외)
                d("1500000"), d("120.50")).validate(TODAY);

        assertTrue(r.ok(), r.errors().toString());
        assertEquals(TODAY, r.clean().asOf());
        assertEquals(List.of("VTI", "005930"), r.clean().holdings().stream().map(Line::symbol).toList());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        var r = new SnapshotInput(TODAY.plusDays(1), List.of(
                new Line("VTI", 5L, null),
                new Line("vti", 3L, null),
                new Line("BAD SYMBOL", 1L, null),
                new Line("BIL", -1L, null)),
                d("-1"), null).validate(TODAY);

        assertFalse(r.ok());
        assertEquals(6, r.errors().size(), r.errors().toString()); // 미래 날짜, 원화 음수, 달러 누락, 중복, 형식, 음수 수량
    }

    @Test
    void cashWithMoreThanTwoDecimalsIsRejected() {
        var r = new SnapshotInput(TODAY, List.of(), d("0"), d("10.123")).validate(TODAY);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("달러 현금")), r.errors().toString());
    }

    // ── 평가 ──

    private static Map<String, Price> prices() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        return Map.of("VTI", new Price(d("375.94"), day), "SCHR", new Price(d("25.00"), day),
                "GLDM", new Price(d("79.00"), day), "BIL", new Price(d("91.50"), day));
    }

    @Test
    void emptyAccountWithOnlyCashIsAllCashAndEveryFundUnderweight() {
        var v = Valuation.of(List.of(), d("0"), d("14000000"), prices(), d("1400"),
                OPEN, true, CORE, PlannerConfig.defaults());

        assertEquals(d("10000.00"), v.totalUsd());
        assertEquals(d("14000000"), v.totalKrw());
        assertEquals(d("100.00"), v.cashWeightPct());
        // VTI 목표 = 35% ÷ 90% × 99% = 38.50%
        var vti = v.rows().stream().filter(r -> r.symbol().equals("VTI")).findFirst().orElseThrow();
        assertEquals(d("38.50"), vti.targetPct());
        assertEquals(d("-38.50"), vti.deviationPct());
        assertTrue(vti.breach());
        assertTrue(v.anyBreach());
    }

    @Test
    void balancedPortfolioHasNoBreachAndExcludesForeignHoldings() {
        // 총 약 2만 달러를 목표에 가깝게: VTI 20주(7,518.80) SCHR 264주(6,600) GLDM 28주(2,212) BIL 36주(3,294) + 현금
        var v = Valuation.of(List.of(
                        new Position("VTI", 20, null), new Position("SCHR", 264, null),
                        new Position("GLDM", 28, null), new Position("BIL", 36, null),
                        new Position("005930", 10, null)),
                d("200"), d("0"), prices(), d("1400"), OPEN, true, CORE, PlannerConfig.defaults());

        assertTrue(v.complete());
        assertFalse(v.anyBreach(), v.rows().toString());
        assertEquals(List.of("005930"), v.excluded().stream().map(Position::symbol).toList());
        assertEquals(d("19824.80"), v.totalUsd());
    }

    @Test
    void missingPriceMeansWeightsAreNotShown() {
        var v = Valuation.of(List.of(new Position("VTI", 1, null)), d("0"), d("0"),
                Map.of("VTI", new Price(d("375"), TODAY)), d("1400"), OPEN, true, CORE, PlannerConfig.defaults());

        assertFalse(v.complete());
        assertEquals(null, v.totalUsd());
        assertEquals(3, v.missing().size(), v.missing().toString());
    }

    @Test
    void breachFlagsMatchTheRebalancingEngine() {
        // 화면의 "이탈" 표시와 엔진의 판정이 어긋나면 안 된다. 여러 상태에서 같은지 확인
        List<List<Position>> cases = List.of(
                List.of(),
                List.of(new Position("VTI", 20, null), new Position("SCHR", 264, null),
                        new Position("GLDM", 28, null), new Position("BIL", 36, null)),
                List.of(new Position("VTI", 30, null), new Position("SCHR", 200, null),
                        new Position("GLDM", 10, null), new Position("BIL", 36, null)),
                List.of(new Position("VTI", 20, null), new Position("SCHR", 264, null),
                        new Position("GLDM", 50, null), new Position("BIL", 10, null)));
        for (List<Position> positions : cases) {
            var view = Valuation.of(positions, d("200"), d("1400000"), prices(), d("1400"),
                    OPEN, true, CORE, PlannerConfig.defaults());
            java.util.Map<String, Long> holdings = new java.util.TreeMap<>();
            positions.forEach(p -> holdings.put(p.symbol(), p.qty()));
            java.util.Map<String, BigDecimal> px = new java.util.TreeMap<>();
            prices().forEach((k, v) -> px.put(k, v.close()));
            var plan = new com.myrobo.portfolio.RebalancePlanner(PlannerConfig.defaults()).plan(
                    new com.myrobo.portfolio.PlanInput(TODAY, false, com.myrobo.portfolio.PlanTypes.Mode.NORMAL,
                            true, OPEN, CORE, px, holdings, d("200"), d("1400000"), d("1400")));

            List<String> viewBreaches = view.rows().stream().filter(Valuation.Row::breach)
                    .map(Valuation.Row::symbol).sorted().toList();
            assertEquals(plan.breachesBefore(), viewBreaches, positions.toString());
        }
    }

    // ── 로컬 요청 보호 ──

    @Test
    void writeRequestsNeedCustomHeader() {
        assertTrue(LocalRequestGuard.check("PUT", "/api/portfolio/snapshot", "localhost:8080", null).isPresent());
        assertTrue(LocalRequestGuard.check("POST", "/internal/jobs/collect", "127.0.0.1:8080", "0").isPresent());
        assertTrue(LocalRequestGuard.check("PUT", "/api/portfolio/snapshot", "localhost:8080", "1").isEmpty());
        assertTrue(LocalRequestGuard.check("GET", "/api/portfolio", "localhost:8080", null).isEmpty());
        assertTrue(LocalRequestGuard.check("GET", "/index.html", "[::1]:8080", null).isEmpty());
    }

    @Test
    void foreignHostIsRejectedEvenForReads() {
        assertTrue(LocalRequestGuard.check("GET", "/api/portfolio", "evil.example:8080", null).isPresent());
        assertTrue(LocalRequestGuard.check("GET", "/", null, null).isPresent());
        assertTrue(LocalRequestGuard.check("GET", "/", "localhost.evil.example", null).isPresent());
    }
}

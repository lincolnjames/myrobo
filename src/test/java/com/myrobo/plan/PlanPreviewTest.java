package com.myrobo.plan;

import com.myrobo.guard.GuardInput;
import com.myrobo.guard.SafetyGuard;
import com.myrobo.guard.SafetyGuard.Report;
import com.myrobo.plan.PlanDecider.Decision;
import com.myrobo.plan.PlanViews.Outcome;
import com.myrobo.plan.PlanViews.PreviewLine;
import com.myrobo.plan.PlanViews.PreviewResult;
import com.myrobo.plan.PlanViews.WeightView;
import com.myrobo.portfolio.PlanInput;
import com.myrobo.portfolio.PlanTypes.Mode;
import com.myrobo.portfolio.PlanTypes.PlannerConfig;
import com.myrobo.portfolio.RebalancePlanner;
import com.myrobo.portfolio.Valuation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문안 미리보기: 만들기와 같은 판정 순서로 예상 결과를 내고, 계산(주문 줄·비중)은 항상 보여 준다.
 * 기본 상황은 {@link PlanSafetyTest}와 같다: BIL 100주 + 원화 10만 원, 기한 없는 프로필.
 */
class PlanPreviewTest {

    private static final Set<String> CORE = Set.of("VTI", "SCHR", "GLDM", "BIL");
    private static final Map<String, BigDecimal> OPEN = Map.of(
            "VTI", d("0.35"), "SATELLITE", d("0.10"), "SCHR", d("0.30"), "GLDM", d("0.10"), "BIL", d("0.15"));
    private static final Map<String, BigDecimal> PRICES = Map.of(
            "VTI", d("373.01"), "SCHR", d("24.00"), "GLDM", d("79.13"), "BIL", d("91.63"));
    private static final BigDecimal FX = d("1324.64");
    private static final RebalancePlanner ENGINE = new RebalancePlanner(PlannerConfig.defaults());
    private static final BigDecimal CAP = d("25");

    // 2026-11-02(월): 주·월 첫 거래일, 목표 시작일
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate NEXT_MON = MON.plusDays(7);

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private record Case(Map<String, Long> holdings, String usd, String krw) {
        PlanInput input(LocalDate day, boolean rebalanceDay) {
            return new PlanInput(day, rebalanceDay, Mode.NORMAL, true, OPEN, CORE, PRICES, holdings, d(usd), d(krw), FX);
        }

        Valuation.View view() {
            List<Valuation.Position> positions = new ArrayList<>();
            holdings.forEach((s, q) -> positions.add(new Valuation.Position(s, q, null)));
            Map<String, Valuation.Price> prices = new TreeMap<>();
            PRICES.forEach((s, p) -> prices.put(s, new Valuation.Price(p, MON.minusDays(3))));
            return Valuation.of(positions, d(usd), d(krw), prices, FX, OPEN, true, CORE, PlannerConfig.defaults());
        }
    }

    private static final Case USER = new Case(Map.of("BIL", 100L), "0", "100000");
    /** 모든 종목이 목표 근처 (PlanSafetyTest.noActionPassesPlanRulesTrivially와 같은 잔고). */
    private static final Case BALANCED = new Case(Map.of("VTI", 10L, "SCHR", 137L, "GLDM", 14L, "BIL", 18L), "100", "0");

    private static TradingSchedule schedule(LocalDate day) {
        boolean monday = day.equals(MON);
        return new TradingSchedule(day, day.minusDays(monday ? 3 : 1), monday, monday, NEXT_MON, day.plusDays(2));
    }

    private static Decision decide(Case c, LocalDate day) {
        boolean monday = day.equals(MON);
        return PlanDecider.decide(ENGINE, c.input(day, monday), monday, NEXT_MON, CAP);
    }

    /** 잔고를 저장한 시각. */
    private static final Instant ENTERED = Instant.parse("2026-11-01T12:00:00Z");

    private static Report guard(Decision dec, LocalDate day, LocalDate asOf, Instant lastClosed, Long open) {
        return SafetyGuard.check(new GuardInput(day, day, MON, asOf, ENTERED, lastClosed,
                open, CORE, Map.of("VTI", 0, "SCHR", 0, "GLDM", 0, "BIL", 0), MON.minusDays(3),
                dec.input(), dec.plan(), Set.of("VTI"), new GuardInput.Limits(2, 7, 3, CAP, d("45"), d("50"))));
    }

    private static Report guard(Decision dec, LocalDate day, LocalDate asOf) {
        return guard(dec, day, asOf, null, null);
    }

    /** 서비스가 하는 대로: 미완료 주문안은 검증 입력에도 넣는다. */
    private static PreviewResult preview(Case c, LocalDate day, Long open, Long today) {
        Decision dec = decide(c, day);
        return PlanPreview.of(dec, guard(dec, day, day, null, open), schedule(day), c.view(), open, null, today,
                MON);
    }

    private static PlanViews.CheckView check(PreviewResult r, SafetyGuard.Rule rule) {
        return r.checks().stream().filter(x -> x.rule().equals(rule.name())).findFirst().orElseThrow();
    }

    private static WeightView weight(PreviewResult r, String symbol) {
        return r.weights().stream().filter(w -> w.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void onSplitDayTheBigRebalanceIsPreviewedAsItWouldBeSent() {
        PreviewResult r = preview(USER, MON, null, null);

        assertEquals(Outcome.SENT, r.predicted());
        assertTrue(r.guardPassed());
        assertEquals("INITIAL_BUILD", r.kind());
        assertTrue(r.sizePct().compareTo(CAP) <= 0, "size " + r.sizePct());
        assertTrue(r.message().startsWith(PlanPreview.PREFIX), r.message());
        assertTrue(r.message().endsWith(PlanPreview.DO_NOT_ORDER), r.message());

        PreviewLine first = r.lines().get(0);
        assertEquals("SELL", first.side());
        assertEquals("BIL", first.symbol());
        assertEquals(d("91.63"), first.estPrice());
        assertTrue(r.lines().stream().filter(l -> l.side().equals("BUY")).count() >= 2);

        // 엔진이 낸 주문 줄과 같아야 한다 (미리보기가 따로 계산하지 않음)
        Decision dec = decide(USER, MON);
        assertEquals(dec.plan().lines().size(), r.lines().size());
        for (int i = 0; i < r.lines().size(); i++) {
            assertEquals(dec.plan().lines().get(i).qty(), r.lines().get(i).qty());
            assertEquals(dec.plan().lines().get(i).estAmountUsd(), r.lines().get(i).estAmountUsd());
        }

        // 지금은 BIL 위주 → 매매 뒤에는 BIL이 줄고 VTI가 는다
        WeightView bil = weight(r, "BIL");
        WeightView vti = weight(r, "VTI");
        assertTrue(bil.breachBefore());
        assertTrue(bil.afterPct().compareTo(bil.currentPct()) < 0, bil.toString());
        assertEquals(0, vti.currentPct().signum());
        assertTrue(vti.afterPct().signum() > 0, vti.toString());
        assertNotNull(vti.breachAfter());
        assertEquals(4, r.weights().size());
    }

    @Test
    void offSplitDayTheBigRebalanceWaitsAndSaysWhen() {
        PreviewResult r = preview(USER, TUE, null, null);

        assertEquals(Outcome.NO_ACTION, r.predicted());
        assertEquals(PlanDecider.SPLIT_WAIT, r.reason());
        assertTrue(r.lines().isEmpty());
        assertTrue(r.message().contains("2026-11-09"), r.message());
        assertFalse(r.message().contains(PlanPreview.DO_NOT_ORDER), r.message());
        // 엔진이 매매 뒤 비중을 계산하지 않았으니 비워 둔다 (현재·목표 비중은 그대로)
        assertNull(weight(r, "BIL").afterPct());
        assertNull(weight(r, "BIL").breachAfter());
        assertNotNull(weight(r, "BIL").currentPct());
    }

    @Test
    void openPlanWinsButTheCalculationIsStillShown() {
        PreviewResult r = preview(USER, MON, 4L, null);

        assertEquals(Outcome.OPEN_EXISTS, r.predicted());
        assertEquals(4L, r.openPlanId());
        assertNull(r.expiringPlanId());
        assertTrue(r.message().contains("#4"), r.message());
        assertFalse(r.lines().isEmpty());
        // 검증 목록도 같은 사실을 말해야 한다 ("미완료 주문안 없음: 통과"로 보이면 안 됨)
        assertFalse(check(r, SafetyGuard.Rule.NO_OPEN_PLAN).passed());
        assertTrue(check(r, SafetyGuard.Rule.NO_OPEN_PLAN).detail().contains("#4"));
        assertFalse(r.guardPassed());
    }

    @Test
    void openPlanWinsEvenWhenThereIsNothingToTrade() {
        PreviewResult r = preview(BALANCED, TUE, 4L, null);

        assertEquals(Outcome.OPEN_EXISTS, r.predicted());
        assertEquals("WITHIN_BAND", r.reason());
    }

    /**
     * 기한 지난 SENT 주문안 #4: 만들기는 먼저 만료시키고(닫힌 시각 = 지금) 새로 계산하는데, 잔고는 그 전에
     * 입력했으니 "지난 주문안 이후 잔고 다시 입력"에 걸려 폐기된다. 미리보기도 같은 결과를 내야 한다.
     */
    @Test
    void overdueOpenPlanIsExpiredFirstAndTheNewPlanIsRejectedLikeCreate() {
        Decision dec = decide(USER, MON);
        Instant now = ENTERED.plusSeconds(3600); // 서비스는 만료될 주문안이 있으면 lastClosed = 지금
        Report report = guard(dec, MON, MON, now, null);
        PreviewResult r = PlanPreview.of(dec, report, schedule(MON), USER.view(), null, 4L, null, MON);

        assertEquals(Outcome.REJECTED, r.predicted());
        assertNull(r.openPlanId());
        assertEquals(4L, r.expiringPlanId());
        assertTrue(r.message().contains("#4") && r.message().contains("만료"), r.message());
        assertTrue(r.message().contains(SafetyGuard.Rule.HOLDINGS_AFTER_LAST_PLAN.label()), r.message());
        assertFalse(check(r, SafetyGuard.Rule.HOLDINGS_AFTER_LAST_PLAN).passed());
        assertTrue(check(r, SafetyGuard.Rule.NO_OPEN_PLAN).passed());
    }

    @Test
    void outcomeOrderIsSharedWithCreate() {
        Decision dec = decide(USER, MON);
        Report passed = guard(dec, MON, MON);
        Report failed = guard(dec, MON, MON.minusDays(8));
        assertTrue(passed.passed());
        assertFalse(failed.passed());

        assertEquals(Outcome.OPEN_EXISTS, PlanService.decideOutcome(1L, dec.plan(), failed, 2L));
        assertEquals(Outcome.ALREADY_TODAY, PlanService.decideOutcome(null, dec.plan(), passed, 2L));
        assertEquals(Outcome.REJECTED, PlanService.decideOutcome(null, dec.plan(), failed, 2L));
        assertEquals(Outcome.SENT, PlanService.decideOutcome(null, dec.plan(), passed, null));
        Decision none = decide(BALANCED, TUE);
        assertEquals(Outcome.NO_ACTION,
                PlanService.decideOutcome(null, none.plan(), guard(none, TUE, TUE), 2L));
    }

    @Test
    void whenTheEngineRanEverySymbolHasAnAfterWeight() {
        PreviewResult r = preview(USER, MON, null, null);
        for (WeightView w : r.weights()) {
            assertNotNull(w.afterPct(), w.toString());
            assertNotNull(w.breachAfter(), w.toString());
        }
    }

    @Test
    void holdingsOutsideTheWhitelistAreListedAsTheEngineExcludedThem() {
        Case withQqq = new Case(Map.of("BIL", 100L, "QQQ", 5L), "0", "100000");
        Decision dec = decide(withQqq, MON);
        assertEquals(List.of("QQQ"), dec.plan().excludedHoldings());

        PreviewResult r = PlanPreview.of(dec, guard(dec, MON, MON), schedule(MON), withQqq.view(), null, null,
                null, MON);
        assertEquals(List.of("QQQ"), r.excluded());
        assertTrue(r.weights().stream().noneMatch(w -> w.symbol().equals("QQQ")));
    }

    @Test
    void secondPlanOnTheSameDayIsPredictedAsAlreadyToday() {
        PreviewResult r = preview(USER, MON, null, 9L);

        assertEquals(Outcome.ALREADY_TODAY, r.predicted());
        assertTrue(r.message().contains("#9"), r.message());
        assertFalse(r.lines().isEmpty());
    }

    @Test
    void failedSafetyCheckIsPredictedAsRejectedWithTheRuleName() {
        Decision dec = decide(USER, MON);
        Report stale = guard(dec, MON, MON.minusDays(8)); // 잔고가 8일 묵음
        PreviewResult r = PlanPreview.of(dec, stale, schedule(MON), USER.view(), null, null, null, MON);

        assertEquals(Outcome.REJECTED, r.predicted());
        assertFalse(r.guardPassed());
        assertTrue(r.message().contains(SafetyGuard.Rule.HOLDINGS_FRESH.label()), r.message());
        // 오늘 이미 있음은 검증을 통과한 주문안에만 해당 (create와 같은 순서)
        PreviewResult withToday = PlanPreview.of(dec, stale, schedule(MON), USER.view(), null, null, 9L, MON);
        assertEquals(Outcome.REJECTED, withToday.predicted());
    }

    @Test
    void withinBandThereIsNothingToTrade() {
        PreviewResult r = preview(BALANCED, TUE, null, null);

        assertEquals(Outcome.NO_ACTION, r.predicted());
        assertEquals("WITHIN_BAND", r.reason());
        assertTrue(r.lines().isEmpty());
        assertTrue(r.weights().stream().noneMatch(WeightView::breachBefore));
        // 매매가 없으니 매매 뒤 비중 = 지금 비중
        for (WeightView w : r.weights()) {
            assertEquals(0, w.afterPct().compareTo(w.currentPct()), w.toString());
            assertFalse(w.breachAfter());
        }
    }
}

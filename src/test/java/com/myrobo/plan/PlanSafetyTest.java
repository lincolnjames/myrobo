package com.myrobo.plan;

import com.myrobo.collector.MarketCalendar;
import com.myrobo.guard.GuardInput;
import com.myrobo.guard.SafetyGuard;
import com.myrobo.guard.SafetyGuard.Report;
import com.myrobo.guard.SafetyGuard.Rule;
import com.myrobo.plan.PlanDecider.Decision;
import com.myrobo.plan.PlanDecider.PlanKind;
import com.myrobo.portfolio.Plan;
import com.myrobo.portfolio.PlanInput;
import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.PlanTypes.FxLine;
import com.myrobo.portfolio.PlanTypes.Mode;
import com.myrobo.portfolio.PlanTypes.OrderLine;
import com.myrobo.portfolio.PlanTypes.PlannerConfig;
import com.myrobo.portfolio.PlanTypes.Side;
import com.myrobo.portfolio.RebalancePlanner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 안전 검증 규칙 하나하나와, 큰 주문안을 매주 나눠 내는 결정, 주문 일정 계산.
 * 기본 상황은 실제 첫 입력과 같다: BIL 100주 + 원화 10만 원, 기한 없는 프로필.
 */
class PlanSafetyTest {

    private static final Set<String> CORE = Set.of("VTI", "SCHR", "GLDM", "BIL");
    private static final Map<String, BigDecimal> OPEN = Map.of(
            "VTI", d("0.35"), "SATELLITE", d("0.10"), "SCHR", d("0.30"), "GLDM", d("0.10"), "BIL", d("0.15"));
    private static final Map<String, BigDecimal> PRICES = Map.of(
            "VTI", d("373.01"), "SCHR", d("24.00"), "GLDM", d("79.13"), "BIL", d("91.63"));
    private static final BigDecimal FX = d("1324.64");
    private static final RebalancePlanner ENGINE = new RebalancePlanner(PlannerConfig.defaults());
    private static final BigDecimal CAP = d("25");

    // 2026-11-02(월): 11월 첫 거래일이자 그 주 첫 거래일, 목표 시작일
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate TUE = MON.plusDays(1);

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static PlanInput input(Map<String, Long> holdings, String usd, String krw) {
        return new PlanInput(MON, false, Mode.NORMAL, true, OPEN, CORE, PRICES, holdings, d(usd), d(krw), FX);
    }

    private static final PlanInput USER = input(Map.of("BIL", 100L), "0", "100000");

    /** 모든 규칙을 통과하는 기본 상황. 테스트마다 한 가지씩 바꿔 본다. */
    private static final class Ctx {
        LocalDate tradeDate = MON;
        LocalDate today = MON;
        LocalDate goalStart = MON;
        LocalDate asOf = MON;
        Instant enteredAt = Instant.parse("2026-11-01T12:00:00Z");
        Instant lastClosed = null;
        Long openPlan = null;
        Map<String, Integer> lag = Map.of("VTI", 0, "SCHR", 0, "GLDM", 0, "BIL", 0);
        LocalDate fxDate = MON.minusDays(3); // 금요일 환율
        PlanInput in;
        Plan plan;

        Ctx(PlanInput in, Plan plan) {
            this.in = in;
            this.plan = plan;
        }

        Report run() {
            return SafetyGuard.check(new GuardInput(tradeDate, today, goalStart, asOf, enteredAt, lastClosed, openPlan,
                    CORE, lag, fxDate, in, plan, Set.of("VTI"),
                    new GuardInput.Limits(2, 7, 3, CAP, d("45"), d("50"))));
        }
    }

    private static Ctx splitDayPlan() {
        Decision dec = PlanDecider.decide(ENGINE, USER, true, MON.plusDays(7), CAP);
        return new Ctx(dec.input(), dec.plan());
    }

    private static Set<Rule> failed(Report r) {
        Set<Rule> out = new java.util.TreeSet<>();
        r.failures().forEach(c -> out.add(c.rule()));
        return out;
    }

    // ── 나눠 내기 ──

    @Test
    void bigRebalanceWaitsForTheWeeklySplitDay() {
        Decision dec = PlanDecider.decide(ENGINE, USER, false, MON.plusDays(7), CAP);

        assertEquals(Action.NO_ACTION, dec.plan().action());
        assertEquals(PlanDecider.SPLIT_WAIT, dec.plan().reason());
        assertTrue(dec.note().contains("2026-11-09"), dec.note());
        assertTrue(dec.plan().lines().isEmpty());
    }

    @Test
    void onSplitDayTheBigRebalanceIsCutTo25PercentAndPassesEveryRule() {
        Decision dec = PlanDecider.decide(ENGINE, USER, true, MON.plusDays(7), CAP);

        assertEquals(PlanKind.INITIAL_BUILD, dec.kind());
        assertEquals(Mode.INITIAL_BUILD, dec.input().mode());
        assertTrue(dec.plan().sizePct().compareTo(CAP) <= 0, "size " + dec.plan().sizePct());
        assertEquals(Side.SELL, dec.plan().lines().get(0).side());
        assertEquals("BIL", dec.plan().lines().get(0).symbol());
        assertTrue(dec.plan().lines().stream().filter(l -> l.side() == Side.BUY).count() >= 2);

        Report r = new Ctx(dec.input(), dec.plan()).run();
        assertTrue(r.passed(), r.failures().toString());
        assertEquals(Rule.values().length, r.checks().size());
    }

    @Test
    void smallDeviationGoesOutAsAnOrdinaryCorePlan() {
        // 목표 근처지만 금이 많이 빠져 허용 범위 밖: 상한 안이라 나누지 않는다
        PlanInput in = input(Map.of("VTI", 21L, "SCHR", 260L, "GLDM", 9L, "BIL", 37L), "150", "0");
        Decision dec = PlanDecider.decide(ENGINE, in, false, MON.plusDays(7), CAP);

        assertEquals(PlanKind.CORE, dec.kind());
        assertTrue(dec.plan().action() != Action.NO_ACTION, dec.plan().reason());
        assertTrue(dec.plan().sizePct().compareTo(CAP) <= 0);
        assertTrue(new Ctx(dec.input(), dec.plan()).run().passed());
    }

    // ── 데이터·상태 규칙 ──

    @Test
    void nothingIsSentBeforeTheGoalStartDate() {
        Ctx c = splitDayPlan();
        c.goalStart = TUE;
        assertEquals(Set.of(Rule.GOAL_STARTED), failed(c.run()));
    }

    @Test
    void holdingsOlderThanSevenDaysBlockThePlan() {
        Ctx c = splitDayPlan();
        c.asOf = MON.minusDays(7);
        assertTrue(c.run().passed());
        c.asOf = MON.minusDays(8);
        assertEquals(Set.of(Rule.HOLDINGS_FRESH), failed(c.run()));
        c.asOf = null;
        assertEquals(Set.of(Rule.HOLDINGS_FRESH), failed(c.run()));
    }

    @Test
    void afterAClosedPlanTheHoldingsMustBeEnteredAgain() {
        Ctx c = splitDayPlan();
        c.lastClosed = c.enteredAt.plusSeconds(60); // 잔고를 입력한 뒤에 지난 주문안이 만료됨
        assertEquals(Set.of(Rule.HOLDINGS_AFTER_LAST_PLAN), failed(c.run()));
        c.enteredAt = c.lastClosed.plusSeconds(1);
        assertTrue(c.run().passed());
    }

    @Test
    void balanceMadeFromExecutionsLetsTheNextPlanThrough() {
        Ctx c = splitDayPlan();
        c.lastClosed = Instant.parse("2026-11-02T01:00:00Z");   // 체결 입력으로 DONE 처리된 시각
        c.enteredAt = c.lastClosed;                             // now()로 저장했다면 같은 시각이라 막힘
        assertEquals(Set.of(Rule.HOLDINGS_AFTER_LAST_PLAN), failed(c.run()));
        c.enteredAt = c.lastClosed.plusNanos(1_000);            // clock_timestamp()는 마이크로초라도 더 늦음
        assertTrue(c.run().passed());
    }

    @Test
    void pricesMustBeFromTheLastTwoSessions() {
        Ctx c = splitDayPlan();
        c.lag = Map.of("VTI", 1, "SCHR", 0, "GLDM", 0, "BIL", 1);
        assertTrue(c.run().passed());
        c.lag = Map.of("VTI", 2, "SCHR", 0, "GLDM", 0, "BIL", 0);
        assertEquals(Set.of(Rule.PRICES_FRESH), failed(c.run()));
        c.lag = Map.of("VTI", 0, "SCHR", 0, "GLDM", 0);
        Report r = c.run();
        assertEquals(Set.of(Rule.PRICES_FRESH), failed(r));
        assertTrue(r.failures().get(0).detail().contains("시세 없음: BIL"), r.failures().get(0).detail());
    }

    @Test
    void fxOlderThanThreeBusinessDaysBlocksThePlanAndWeekendsDoNotCount() {
        Ctx c = splitDayPlan();
        c.today = LocalDate.of(2026, 11, 4);     // 수요일
        c.fxDate = LocalDate.of(2026, 10, 30);   // 금요일 → 월·화·수 = 3영업일
        assertTrue(c.run().passed());
        c.fxDate = LocalDate.of(2026, 10, 29);   // 목요일 → 4영업일
        assertEquals(Set.of(Rule.FX_FRESH), failed(c.run()));
        c.fxDate = null;
        assertEquals(Set.of(Rule.FX_FRESH), failed(c.run()));
    }

    @Test
    void anOpenPlanBlocksANewOne() {
        Ctx c = splitDayPlan();
        c.openPlan = 7L;
        assertEquals(Set.of(Rule.NO_OPEN_PLAN), failed(c.run()));
    }

    // ── 엔진 결과를 믿지 않고 다시 확인 ──

    private static Plan plan(List<OrderLine> lines, FxLine fx) {
        return new Plan(Action.FULL, "BAND_BREACH", null, lines, fx, d("1"), d("1"), d("9238.49"),
                List.of(), List.of(), new TreeMap<>(), d("0"), d("0"), List.of());
    }

    @Test
    void sellingMoreThanHeldOrUnknownSymbolsOrWrongAmountsAreRejected() {
        Ctx c = new Ctx(USER, plan(List.of(
                new OrderLine(Side.SELL, "BIL", 101, d("9254.63")),     // 100주 보유
                new OrderLine(Side.BUY, "TQQQ", 10, d("900.00")),       // 화이트리스트 밖
                new OrderLine(Side.BUY, "VTI", 2, d("700.00"))), null)); // 2 × 373.01 = 746.02
        Report r = c.run();
        assertTrue(failed(r).contains(Rule.ORDER_INTEGRITY));
        String detail = r.checks().get(Rule.ORDER_INTEGRITY.ordinal()).detail();
        assertTrue(detail.contains("BIL 101주 매도 > 보유 100주"), detail);
        assertTrue(detail.contains("TQQQ"), detail);
        assertTrue(detail.contains("VTI 예상 금액"), detail);
        // 가격 없는 종목이 섞이면 주문 후 상태도 계산할 수 없으니 나머지 규칙도 통과시키지 않는다
        assertTrue(failed(r).containsAll(Set.of(Rule.CASH_NON_NEGATIVE, Rule.SIZE_CAP, Rule.EQUITY_CAP)));
    }

    @Test
    void buyingWithMoneyThatIsNotThereIsRejected() {
        // 원화 10만 원(약 75달러)뿐인데 VTI 1주(373달러)를 환전 없이 산다
        Ctx c = new Ctx(USER, plan(List.of(new OrderLine(Side.BUY, "VTI", 1, d("373.01"))), null));
        assertEquals(Set.of(Rule.CASH_NON_NEGATIVE), failed(c.run()));

        // 환전 줄을 넣어도 원화가 모자라면 마찬가지
        long krw = d("373").multiply(FX).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        c.plan = plan(List.of(new OrderLine(Side.BUY, "VTI", 1, d("373.01"))), new FxLine(373, krw));
        assertEquals(Set.of(Rule.CASH_NON_NEGATIVE), failed(c.run()));
    }

    @Test
    void anUnsplitPlanAboveTheSizeCapIsRejected() {
        Plan unsplit = ENGINE.plan(USER); // 엔진은 평소 방식으로는 크기를 줄이지 않는다
        assertTrue(unsplit.sizePct().compareTo(CAP) > 0, "size " + unsplit.sizePct());
        Ctx c = new Ctx(USER, unsplit);
        assertEquals(Set.of(Rule.SIZE_CAP), failed(c.run()));
    }

    @Test
    void equityCapBlocksIncreasesButNotReductions() {
        // 원화만 있는 계좌에서 VTI만 잔뜩 사는 주문 → 주식 비중이 45%를 넘음
        PlanInput cash = input(Map.of(), "10000", "0");
        Ctx c = new Ctx(cash, plan(List.of(new OrderLine(Side.BUY, "VTI", 15, d("5595.15"))), null));
        Report r = c.run();
        assertTrue(failed(r).contains(Rule.EQUITY_CAP), r.failures().toString());

        // 이미 60%인데 50%로 줄이는 주문은 상한 위라도 통과 (막으면 영영 못 줄임)
        PlanInput heavy = input(Map.of("VTI", 16L, "BIL", 42L), "130", "0"); // VTI ≈ 5,968 / 총 ≈ 9,947
        Ctx down = new Ctx(heavy, plan(List.of(new OrderLine(Side.SELL, "VTI", 3, d("1119.03")),
                new OrderLine(Side.BUY, "BIL", 12, d("1099.56"))), null));
        Report rd = down.run();
        assertFalse(failed(rd).contains(Rule.EQUITY_CAP), rd.failures().toString());
        assertTrue(rd.checks().get(Rule.EQUITY_CAP.ordinal()).detail().contains("줄이는 주문"));
    }

    @Test
    void engineErrorFailsEveryPlanRule() {
        Ctx c = new Ctx(null, Plan.error("MISSING_FX", "환율이 없음"));
        Set<Rule> f = failed(c.run());
        assertTrue(f.containsAll(Set.of(Rule.ENGINE_OK, Rule.ORDER_INTEGRITY, Rule.CASH_NON_NEGATIVE,
                Rule.SIZE_CAP, Rule.EQUITY_CAP)), f.toString());
    }

    @Test
    void noActionPassesPlanRulesTrivially() {
        // 모든 종목이 목표 근처 (VTI 37.8%, SCHR 33.3%, GLDM 11.2%, BIL 16.7%), 정기일 아님
        PlanInput balanced = input(Map.of("VTI", 10L, "SCHR", 137L, "GLDM", 14L, "BIL", 18L), "100", "0");
        Plan none = ENGINE.plan(balanced);
        assertEquals(Action.NO_ACTION, none.action());
        assertEquals("WITHIN_BAND", none.reason());
        Report r = new Ctx(balanced, none).run();
        assertTrue(r.passed(), r.failures().toString());
        assertEquals("주문 없음", r.checks().get(Rule.SIZE_CAP.ordinal()).detail());
    }

    @Test
    void noActionMessageOnlyDoubtsTheDecisionWhenDataRulesFail() {
        Decision dec = PlanDecider.decide(ENGINE, USER, false, MON, CAP);
        Ctx c = new Ctx(dec.input(), dec.plan());
        c.tradeDate = LocalDate.of(2026, 10, 27);
        c.goalStart = MON;
        String beforeStart = PlanService.noActionMessage(dec.plan(), c.run(), MON);
        assertTrue(beforeStart.contains("목표 시작일 2026-11-02 전"), beforeStart);
        assertFalse(beforeStart.contains("믿을 수 없어요"), beforeStart);

        c.fxDate = null;
        String stale = PlanService.noActionMessage(dec.plan(), c.run(), MON);
        assertTrue(stale.contains("환율 신선도 검증에 걸려 이 판단도 믿을 수 없어요"), stale);
    }

    // ── 일정 ──

    @Test
    void scheduleFollowsTheUsTradingCalendar() {
        // 2026-11-26 추수감사절 휴장
        MarketCalendar cal = new MarketCalendar(Set.of(LocalDate.of(2026, 11, 26)));

        TradingSchedule mon = TradingSchedule.of(cal, MON, 3);
        assertEquals(MON, mon.tradeDate());
        assertEquals(LocalDate.of(2026, 10, 30), mon.lastSession());
        assertTrue(mon.rebalanceDay());
        assertTrue(mon.splitDay());
        assertEquals(LocalDate.of(2026, 11, 9), mon.nextSplitDay());
        assertEquals(LocalDate.of(2026, 11, 4), mon.expiresAt());

        // 토요일에 만들면 월요일 장 기준
        TradingSchedule sat = TradingSchedule.of(cal, LocalDate.of(2026, 10, 31), 3);
        assertEquals(MON, sat.tradeDate());

        // 화요일(오늘 사용자 상황): 정기일도 분할일도 아님
        TradingSchedule tue = TradingSchedule.of(cal, LocalDate.of(2026, 10, 27), 3);
        assertFalse(tue.rebalanceDay());
        assertFalse(tue.splitDay());
        assertEquals(MON, tue.nextSplitDay());

        // 휴장일을 건너뛰어 만료일 계산: 수요일에 만들면 수·금·월
        assertEquals(LocalDate.of(2026, 11, 30), TradingSchedule.of(cal, LocalDate.of(2026, 11, 25), 3).expiresAt());
        assertEquals(2, cal.tradingDaysAfter(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 27)));
    }

    @Test
    void mondayHolidayMakesTuesdayTheFirstTradingDayOfTheWeek() {
        LocalDate mlk = LocalDate.of(2026, 1, 19);
        MarketCalendar cal = new MarketCalendar(Set.of(mlk));
        assertFalse(cal.isFirstTradingDayOfWeek(mlk));
        assertTrue(cal.isFirstTradingDayOfWeek(mlk.plusDays(1)));
        assertFalse(cal.isFirstTradingDayOfWeek(mlk.plusDays(2)));
        // 1월 1일 휴장이면 1월 2일이 그달 첫 거래일
        MarketCalendar ny = new MarketCalendar(Set.of(LocalDate.of(2026, 1, 1)));
        assertTrue(ny.isFirstTradingDayOfMonth(LocalDate.of(2026, 1, 2)));
    }
}

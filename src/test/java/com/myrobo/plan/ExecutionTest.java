package com.myrobo.plan;

import com.myrobo.holdings.HoldingsRepository;
import com.myrobo.holdings.HoldingsRepository.Cash;
import com.myrobo.holdings.HoldingsService;
import com.myrobo.holdings.HoldingsService.State;
import com.myrobo.holdings.SnapshotInput;
import com.myrobo.plan.ExecutionInput.Fill;
import com.myrobo.plan.ExecutionInput.Fx;
import com.myrobo.plan.ExecutionInput.Item;
import com.myrobo.plan.PlanViews.ExecutionResult;
import com.myrobo.plan.PlanViews.ExecutionStatus;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;
import com.myrobo.portfolio.Valuation.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 체결 입력: 검증, 새 잔고 계산, 환율 차이, 그리고 저장 순서·상태 조건(DB 없이 가짜 저장소로). */
class ExecutionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z"); // 한국 10:00
    private static final Instant PLAN_CREATED = Instant.parse("2026-11-01T23:00:00Z");

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    // 주문안: VTI 10주, GLDM 20주 매수. 달러가 없어 6,000달러를 환전해 사는 상황 (예상 환율 1300)
    private static final List<Item> ITEMS = List.of(
            new Item(1, "BUY", "VTI", 10), new Item(2, "BUY", "GLDM", 20));
    private static final List<Position> HELD = List.of(new Position("BIL", 100, d("91.00")));

    private static Fill fill(long item, long qty, String price, String fee) {
        return new Fill(item, qty, price == null ? null : d(price), fee == null ? null : d(fee));
    }

    private static ExecutionInput.Clean clean(ExecutionInput in) {
        ExecutionInput.Result r = in.validate(ITEMS);
        assertTrue(r.ok(), r.errors().toString());
        return r.clean();
    }

    private static ExecutionInput.Applied apply(ExecutionInput in, List<Position> held, String krw, String usd) {
        return ExecutionInput.apply(clean(in), held, d(krw), d(usd), TODAY);
    }

    private static Map<String, Long> qtys(SnapshotInput.Clean s) {
        return s.holdings().stream().collect(Collectors.toMap(SnapshotInput.Line::symbol, SnapshotInput.Line::qty));
    }

    // ── 새 잔고 계산 ──

    @Test
    void fullFillAddsQuantitiesAndMovesCash() {
        var in = new ExecutionInput(List.of(fill(1, 10, "373.01", "0.50"), fill(2, 20, "79.13", "0.30")),
                new Fx(d("6000.00"), d("7878000.00")));

        var a = apply(in, HELD, "10000000", "0");

        assertTrue(a.ok(), a.errors().toString());
        assertEquals(Map.of("BIL", 100L, "VTI", 10L, "GLDM", 20L), qtys(a.snapshot()));
        assertEquals(TODAY, a.snapshot().asOf());
        // 6000 - (3730.10 + 1582.60) - 0.80 = 686.50
        assertEquals(d("686.50"), a.snapshot().cashUsd());
        assertEquals(d("2122000.00"), a.snapshot().cashKrw());
        var vti = a.snapshot().holdings().stream().filter(l -> l.symbol().equals("VTI")).findFirst().orElseThrow();
        assertEquals(0, d("373.01").compareTo(vti.avgPrice()));
    }

    @Test
    void partialFillCountsOnlyWhatWasFilled() {
        var in = new ExecutionInput(List.of(fill(1, 4, "373.01", "0.20"), fill(2, 0, null, null)),
                new Fx(d("6000.00"), d("7878000.00")));

        ExecutionInput.Clean c = clean(in);
        var a = ExecutionInput.apply(c, HELD, d("10000000"), d("0"), TODAY);

        assertEquals(1, c.unfilled());
        assertEquals(1, c.executed().size());
        assertEquals(Map.of("BIL", 100L, "VTI", 4L), qtys(a.snapshot())); // GLDM은 잔고에 없다
        assertEquals(d("4507.76"), a.snapshot().cashUsd()); // 6000 - 1492.04 - 0.20
    }

    @Test
    void nothingFilledLeavesTheBalanceAsItWas() {
        var in = new ExecutionInput(List.of(fill(1, 0, null, null), fill(2, 0, null, null)), null);

        var a = apply(in, HELD, "100000", "50.00");

        assertTrue(a.ok());
        assertEquals(Map.of("BIL", 100L), qtys(a.snapshot()));
        assertEquals(d("50.00"), a.snapshot().cashUsd());
        assertEquals(d("100000.00"), a.snapshot().cashKrw());
    }

    @Test
    void buyingMoreOfAHeldSymbolAveragesThePrice() {
        var held = List.of(new Position("VTI", 10, d("300.00")));
        var in = new ExecutionInput(List.of(fill(1, 10, "400.00", null), fill(2, 0, null, null)), null);

        var a = apply(in, held, "0", "5000.00");

        var vti = a.snapshot().holdings().get(0);
        assertEquals(20L, vti.qty());
        assertEquals(0, d("350").compareTo(vti.avgPrice()));
    }

    @Test
    void unknownPreviousAverageStaysUnknown() {
        var held = List.of(new Position("VTI", 10, null));
        var in = new ExecutionInput(List.of(fill(1, 10, "400.00", null), fill(2, 0, null, null)), null);

        assertNull(apply(in, held, "0", "5000.00").snapshot().holdings().get(0).avgPrice());
    }

    @Test
    void sellingReducesQuantityAndRaisesCash() {
        var items = List.of(new Item(7, "SELL", "BIL", 40));
        var r = new ExecutionInput(List.of(fill(7, 40, "91.63", "0.10")), null).validate(items);

        var a = ExecutionInput.apply(r.clean(), HELD, d("0"), d("0"), TODAY);

        assertEquals(Map.of("BIL", 60L), qtys(a.snapshot()));
        assertEquals(d("3665.10"), a.snapshot().cashUsd()); // 40 × 91.63 = 3665.20, 수수료 0.10 제외
    }

    // ── 거부해야 하는 입력 ──

    @Test
    void fillingMoreThanPlannedIsRejected() {
        var r = new ExecutionInput(List.of(fill(1, 11, "373.01", null), fill(2, 20, "79.13", null)), null)
                .validate(ITEMS);

        assertFalse(r.ok());
        assertTrue(r.errors().get(0).contains("계획 10주보다 많음"), r.errors().toString());
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        var r = new ExecutionInput(List.of(
                fill(1, 5, null, null),          // 가격 없음
                new Fill(99L, 1L, d("1"), null), // 없는 줄
                new Fill(1L, 1L, d("1"), null)), // 중복
                new Fx(d("100"), null)).validate(ITEMS);

        assertFalse(r.ok());
        assertEquals(5, r.errors().size(), r.errors().toString()); // 가격, 없는 줄, 중복, GLDM 누락, 환전 한쪽만
    }

    @Test
    void aLineLeftOutIsNotTreatedAsUnfilled() {
        var r = new ExecutionInput(List.of(fill(1, 10, "373.01", null)), null).validate(ITEMS);

        assertFalse(r.ok());
        assertTrue(r.errors().get(0).contains("GLDM"), r.errors().toString());
    }

    @Test
    void negativeQuantityPriceOrFeeAreRejected() {
        assertFalse(new ExecutionInput(List.of(fill(1, -1, "1", null), fill(2, 0, null, null)), null)
                .validate(ITEMS).ok());
        assertFalse(new ExecutionInput(List.of(fill(1, 1, "0", null), fill(2, 0, null, null)), null)
                .validate(ITEMS).ok());
        assertFalse(new ExecutionInput(List.of(fill(1, 1, "1", "-0.01"), fill(2, 0, null, null)), null)
                .validate(ITEMS).ok());
        assertFalse(new ExecutionInput(List.of(fill(1, 0, null, "0.5"), fill(2, 0, null, null)), null)
                .validate(ITEMS).ok()); // 미체결인데 수수료
    }

    @Test
    void anImpossibleResultIsRejectedInsteadOfSaved() {
        var noFx = new ExecutionInput(List.of(fill(1, 10, "373.01", null), fill(2, 20, "79.13", null)), null);
        var a = apply(noFx, HELD, "10000000", "0"); // 달러가 없는데 매수 체결

        assertFalse(a.ok());
        assertTrue(a.errors().get(0).contains("달러 현금"), a.errors().toString());

        var tooMuchFx = new ExecutionInput(List.of(fill(1, 0, null, null), fill(2, 0, null, null)),
                new Fx(d("1000.00"), d("2000000.00")));
        var b = apply(tooMuchFx, HELD, "100000", "0"); // 가진 원화보다 많이 환전

        assertFalse(b.ok());
        assertTrue(b.errors().get(0).contains("원화 현금"), b.errors().toString());

        var items = List.of(new Item(7, "SELL", "BIL", 150));
        var r = new ExecutionInput(List.of(fill(7, 150, "91.63", null)), null).validate(items);
        var c = ExecutionInput.apply(r.clean(), HELD, d("0"), d("0"), TODAY); // 100주 보유인데 150주 매도

        assertFalse(c.ok());
        assertTrue(c.errors().get(0).contains("보유 100주보다 많음"), c.errors().toString());
    }

    // ── 환율 차이 ──

    @Test
    void fxDifferenceAgainstThePlanIsReported() {
        var fx = ExecutionInput.fxCheck(new Fx(d("6000.00"), d("7878000.00")), 6000L, 7_800_000L);

        assertEquals(0, d("1300").compareTo(fx.expectedRate()));
        assertEquals(0, d("1313").compareTo(fx.actualRate()));
        assertEquals(0, d("1.00").compareTo(fx.diffPct())); // 예상보다 1% 비싸게 환전
        assertNull(ExecutionInput.fxCheck(null, 6000L, 7_800_000L));
        assertNull(ExecutionInput.fxCheck(new Fx(d("10"), d("13000")), null, null).diffPct());
    }

    @Test
    void aBalanceEnteredAfterThePlanIsTreatedAsAlreadyCounting() {
        assertTrue(ExecutionInput.balanceEnteredAfterPlan(PLAN_CREATED.plusSeconds(1), PLAN_CREATED));
        assertFalse(ExecutionInput.balanceEnteredAfterPlan(PLAN_CREATED.minusSeconds(60), PLAN_CREATED));
        assertFalse(ExecutionInput.balanceEnteredAfterPlan(null, PLAN_CREATED));
    }

    // ── 저장 순서·상태 조건 (가짜 저장소) ──

    /** 호출을 순서대로 기록하는 가짜 저장소들. */
    private static final class Fakes {
        final List<String> calls = new ArrayList<>();
        String planStatus = "SENT";
        Instant balanceEnteredAt = PLAN_CREATED.minusSeconds(3600);
        long openPlanId = 5;

        private PlanView plan() {
            return new PlanView(5, TODAY, "CORE", planStatus, "REBALANCE", "BAND_BREACH", null, d("5312.70"),
                    d("20"), 6000L, 7_800_000L, TODAY.plusDays(3),
                    OffsetDateTime.ofInstant(PLAN_CREATED, ZoneOffset.UTC),
                    List.of(new ItemView(1, "BUY", "VTI", 10, d("373.01"), d("3730.10")),
                            new ItemView(2, "BUY", "GLDM", 20, d("79.13"), d("1582.60"))), List.of());
        }

        final PlanRepository plans = new PlanRepository(null) {
            @Override public void lock(long account) { calls.add("lock"); }
            @Override public Optional<Long> findOpen(long account) { return Optional.of(openPlanId); }
            @Override public Optional<PlanView> load(long id) { return Optional.of(plan()); }
            @Override public void transition(long id, String from, String to) { calls.add("transition " + from + ">" + to); }
            @Override public void insertExecution(ExecutionInput.Executed e, OffsetDateTime at) { calls.add("execution " + e.symbol() + " " + e.qty()); }
        };
        final HoldingsRepository holdingsRepo = new HoldingsRepository(null) {
            @Override public void insertLedger(long a, LocalDate date, String type, String cur, BigDecimal amt, String memo) { calls.add("ledger " + type + " " + cur + " " + amt.toPlainString()); }
            @Override public void replaceSnapshotAfterClose(long a, SnapshotInput.Clean s) { calls.add("snapshot " + s.asOf()); }
        };
        final HoldingsService holdings = new HoldingsService(null, null) {
            @Override public State state() {
                return new State(1, TODAY.minusDays(1), balanceEnteredAt, HELD, new Cash(d("10000000"), d("0")), null, Map.of());
            }
            @Override public PortfolioResponse view() { return null; }
        };

        ExecutionResult save(ExecutionInput in) {
            return new ExecutionService(holdings, holdingsRepo, plans, Clock.fixed(NOW, ZoneOffset.UTC)).save(5, in);
        }
    }

    private static final ExecutionInput FULL = new ExecutionInput(
            List.of(fill(1, 10, "373.01", "0.50"), fill(2, 20, "79.13", "0.30")), new Fx(d("6000.00"), d("7878000.00")));

    @Test
    void savingClosesThePlanFirstAndThenWritesTheBalance() {
        var f = new Fakes();

        ExecutionResult r = f.save(FULL);

        assertEquals(ExecutionStatus.SAVED, r.status(), r.errors().toString());
        // 주문안을 먼저 닫고(now), 잔고는 그 뒤에 저장(clock_timestamp)해야 HOLDINGS_AFTER_LAST_PLAN을 통과한다
        assertEquals(List.of("lock", "transition SENT>DONE", "execution VTI 10", "execution GLDM 20",
                "ledger FX USD 6000.00", "ledger FX KRW -7878000.00", "snapshot 2026-11-02"), f.calls);
        assertEquals(0, d("1.00").compareTo(r.fx().diffPct()));
        assertTrue(r.message().contains("증권사 앱"), r.message());
    }

    @Test
    void planThatIsNotSentCannotTakeExecutions() {
        for (String status : List.of("DONE", "EXPIRED", "REJECTED", "DRAFT")) {
            var f = new Fakes();
            f.planStatus = status;

            ExecutionResult r = f.save(FULL);

            assertEquals(ExecutionStatus.CONFLICT, r.status(), status);
            assertEquals(List.of("lock"), f.calls, status); // 아무것도 쓰지 않음
        }
    }

    @Test
    void anotherPlanIdCannotBeUsedToWriteThisPlansExecutions() {
        var f = new Fakes();
        f.openPlanId = 6; // 열려 있는 주문안은 6번뿐

        assertEquals(ExecutionStatus.CONFLICT, f.save(FULL).status());
        assertEquals(List.of("lock"), f.calls);
    }

    @Test
    void balanceEnteredAfterThePlanBlocksExecutionEntry() {
        var f = new Fakes();
        f.balanceEnteredAt = PLAN_CREATED.plusSeconds(600);

        ExecutionResult r = f.save(FULL);

        assertEquals(ExecutionStatus.CONFLICT, r.status());
        assertTrue(r.message().contains("이중"), r.message());
        assertEquals(List.of("lock"), f.calls);
    }

    @Test
    void invalidInputWritesNothing() {
        var f = new Fakes();

        ExecutionResult over = f.save(new ExecutionInput(List.of(fill(1, 11, "373.01", null), fill(2, 20, "79.13", null)), null));
        ExecutionResult noCash = f.save(new ExecutionInput(List.of(fill(1, 10, "373.01", null), fill(2, 20, "79.13", null)), null));

        assertEquals(ExecutionStatus.INVALID, over.status());
        assertEquals(ExecutionStatus.INVALID, noCash.status());
        assertEquals(List.of("lock", "lock"), f.calls);
    }
}

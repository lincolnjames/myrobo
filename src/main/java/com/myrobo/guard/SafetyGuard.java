package com.myrobo.guard;

import com.myrobo.portfolio.Plan;
import com.myrobo.portfolio.PlanInput;
import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.PlanTypes.FxLine;
import com.myrobo.portfolio.PlanTypes.OrderLine;
import com.myrobo.portfolio.PlanTypes.Side;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 주문안 안전 검증. 규칙을 하나라도 어기면 주문안 전체를 버린다 (일부만 실행하는 경로는 없다).
 *
 * <p>엔진이 이미 지키는 불변식(보유보다 많이 팔지 않음, 현금이 음수가 되지 않음 등)도 엔진 결과를 믿지 않고
 * 입력에서 다시 계산해 확인한다. 엔진에 버그가 생겨도 잘못된 주문이 나가지 않게 하려는 이중 장치다.
 *
 * <p>순수 함수: 같은 입력이면 항상 같은 결과.
 */
public final class SafetyGuard {

    /** 규칙 코드와 화면용 이름. 순서가 곧 표시 순서다. */
    public enum Rule {
        ENGINE_OK("계산 성공"),
        GOAL_STARTED("목표 시작일 이후"),
        HOLDINGS_FRESH("잔고 신선도"),
        HOLDINGS_AFTER_LAST_PLAN("지난 주문안 이후 잔고 다시 입력"),
        PRICES_FRESH("시세 신선도"),
        FX_FRESH("환율 신선도"),
        NO_OPEN_PLAN("미완료 주문안 없음"),
        ORDER_INTEGRITY("주문 줄 정합성"),
        CASH_NON_NEGATIVE("현금이 음수가 되지 않음"),
        SIZE_CAP("주문안 크기 상한"),
        EQUITY_CAP("주식 비중 상한");

        private final String label;

        Rule(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Check(Rule rule, boolean passed, String detail) {
        public String label() {
            return rule.label();
        }
    }

    public record Report(List<Check> checks) {
        public Report {
            checks = List.copyOf(checks);
        }

        public boolean passed() {
            return checks.stream().allMatch(Check::passed);
        }

        public List<Check> failures() {
            return checks.stream().filter(c -> !c.passed()).toList();
        }
    }

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final BigDecimal PCT_TOLERANCE = new BigDecimal("0.005"); // 출력이 소수 둘째 자리로 반올림됨

    private SafetyGuard() {
    }

    public static Report check(GuardInput g) {
        List<Check> out = new ArrayList<>();
        out.add(engineOk(g));
        out.add(goalStarted(g));
        out.add(holdingsFresh(g));
        out.add(holdingsAfterLastPlan(g));
        out.add(pricesFresh(g));
        out.add(fxFresh(g));
        out.add(noOpenPlan(g));

        Effect e = effect(g);
        out.add(planRule(g, Rule.ORDER_INTEGRITY, () -> integrity(g)));
        out.add(planRule(g, Rule.CASH_NON_NEGATIVE, () -> e == null ? unknown(Rule.CASH_NON_NEGATIVE) : cash(e)));
        out.add(planRule(g, Rule.SIZE_CAP, () -> e == null ? unknown(Rule.SIZE_CAP) : size(g, e)));
        out.add(planRule(g, Rule.EQUITY_CAP, () -> e == null ? unknown(Rule.EQUITY_CAP) : equity(g, e)));
        return new Report(out);
    }

    // ───────────────────────── 데이터·상태 규칙 ─────────────────────────

    private static Check engineOk(GuardInput g) {
        Plan p = g.plan();
        if (p.action() == Action.ERROR) {
            return fail(Rule.ENGINE_OK, "계산 실패: " + p.reason() + (p.detail() == null ? "" : " (" + p.detail() + ")"));
        }
        return pass(Rule.ENGINE_OK, p.action() + " / " + p.reason());
    }

    private static Check goalStarted(GuardInput g) {
        if (g.tradeDate().isBefore(g.goalStart())) {
            return fail(Rule.GOAL_STARTED, "목표 시작일 " + g.goalStart() + " 전 (주문일 " + g.tradeDate() + ")");
        }
        return pass(Rule.GOAL_STARTED, "시작일 " + g.goalStart());
    }

    private static Check holdingsFresh(GuardInput g) {
        if (g.snapshotAsOf() == null) {
            return fail(Rule.HOLDINGS_FRESH, "입력된 잔고 없음");
        }
        long age = ChronoUnit.DAYS.between(g.snapshotAsOf(), g.todayKst());
        int max = g.limits().holdingsDays();
        if (age < 0) {
            return fail(Rule.HOLDINGS_FRESH, "잔고 기준일 " + g.snapshotAsOf() + "이 미래");
        }
        return age <= max
                ? pass(Rule.HOLDINGS_FRESH, age + "일 전 잔고 (" + max + "일까지 허용)")
                : fail(Rule.HOLDINGS_FRESH, age + "일 전 잔고. " + max + "일 넘으면 잔고를 다시 입력해야 함");
    }

    /** 만료·완료된 주문안 뒤에는 실제 체결 결과가 반영된 잔고로만 다시 계산한다. */
    private static Check holdingsAfterLastPlan(GuardInput g) {
        if (g.lastClosedPlanAt() == null) {
            return pass(Rule.HOLDINGS_AFTER_LAST_PLAN, "이전 주문안 없음");
        }
        if (g.snapshotEnteredAt() == null || !g.snapshotEnteredAt().isAfter(g.lastClosedPlanAt())) {
            return fail(Rule.HOLDINGS_AFTER_LAST_PLAN,
                    "지난 주문안이 닫힌 뒤 잔고를 다시 입력하지 않음. 일부만 체결됐을 수 있으니 실제 잔고를 입력해야 함");
        }
        return pass(Rule.HOLDINGS_AFTER_LAST_PLAN, "지난 주문안 뒤에 입력한 잔고");
    }

    private static Check pricesFresh(GuardInput g) {
        int allowedLag = g.limits().priceTradingDays() - 1;
        TreeSet<String> missing = new TreeSet<>();
        List<String> stale = new ArrayList<>();
        for (String s : new TreeSet<>(g.priceRequired())) {
            Integer lag = g.priceLag().get(s);
            if (lag == null) {
                missing.add(s);
            } else if (lag > allowedLag) {
                stale.add(s + " " + lag + "거래일 늦음");
            }
        }
        if (!missing.isEmpty() || !stale.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!missing.isEmpty()) {
                parts.add("시세 없음: " + String.join(", ", missing));
            }
            if (!stale.isEmpty()) {
                parts.add("오래됨: " + String.join(", ", stale));
            }
            return fail(Rule.PRICES_FRESH, String.join(" / ", parts) + " (수집을 먼저 돌려야 함)");
        }
        return pass(Rule.PRICES_FRESH, g.priceRequired().size() + "종목 모두 최근 "
                + g.limits().priceTradingDays() + "거래일 안의 시세");
    }

    private static Check fxFresh(GuardInput g) {
        if (g.fxDate() == null) {
            return fail(Rule.FX_FRESH, "환율 없음");
        }
        int lag = businessDaysAfter(g.fxDate(), g.todayKst());
        int max = g.limits().fxMaxAgeBusinessDays();
        return lag <= max
                ? pass(Rule.FX_FRESH, g.fxDate() + " 환율 (" + lag + "영업일 전)")
                : fail(Rule.FX_FRESH, g.fxDate() + " 환율이 " + lag + "영업일 전. " + max + "영업일까지 허용");
    }

    private static Check noOpenPlan(GuardInput g) {
        return g.openPlanId() == null
                ? pass(Rule.NO_OPEN_PLAN, "없음")
                : fail(Rule.NO_OPEN_PLAN, "주문안 #" + g.openPlanId() + "이 아직 완료·만료되지 않음");
    }

    // ───────────────────────── 주문안 규칙 ─────────────────────────

    private interface RuleBody {
        Check run();
    }

    private static boolean computable(GuardInput g) {
        return g.input() != null && g.plan().action() != Action.ERROR;
    }

    /** 주문 줄이 엉망이면(가격 없는 종목 등) 결과를 계산할 수 없다. 그때는 null이고 관련 규칙은 모두 실패한다. */
    private static Effect effect(GuardInput g) {
        if (!computable(g)) {
            return null;
        }
        try {
            return Effect.of(g.input(), g.plan(), g.equitySymbols());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Check unknown(Rule rule) {
        return fail(rule, "주문 줄이 잘못돼 주문 후 상태를 계산할 수 없음");
    }

    private static Check planRule(GuardInput g, Rule rule, RuleBody body) {
        if (!computable(g)) {
            return fail(rule, "계산 결과가 없어 확인할 수 없음");
        }
        if (g.plan().lines().isEmpty() && g.plan().fx() == null) {
            return pass(rule, "주문 없음");
        }
        try {
            return body.run();
        } catch (RuntimeException ex) { // 예상 못 한 형태의 결과는 통과시키지 않는다
            return fail(rule, "확인 중 오류: " + ex.getMessage());
        }
    }

    private static Check integrity(GuardInput g) {
        PlanInput in = g.input();
        Plan p = g.plan();
        List<String> problems = new ArrayList<>();
        Set<String> sold = new HashSet<>();
        Set<String> bought = new HashSet<>();
        boolean anyBuy = false;
        for (OrderLine l : p.lines()) {
            String s = l.symbol();
            if (l.qty() <= 0) {
                problems.add(s + " 수량이 0 이하");
            }
            if (!in.whitelist().contains(s)) {
                problems.add(s + "는 시스템이 다루는 종목이 아님");
                continue;
            }
            BigDecimal price = in.prices().get(s);
            if (price == null) {
                problems.add(s + " 가격 없음");
                continue;
            }
            BigDecimal expect = price.multiply(BigDecimal.valueOf(l.qty()), MC);
            if (l.estAmountUsd().subtract(expect, MC).abs().compareTo(CENT) > 0) {
                problems.add(s + " 예상 금액 " + l.estAmountUsd() + "이 수량×가격 " + money(expect) + "과 다름");
            }
            if (l.side() == Side.SELL) {
                long held = in.holdings().getOrDefault(s, 0L);
                if (l.qty() > held) {
                    problems.add(s + " " + l.qty() + "주 매도 > 보유 " + held + "주");
                }
                if (!sold.add(s)) {
                    problems.add(s + " 매도 줄 중복");
                }
            } else {
                anyBuy = true;
                if (l.estAmountUsd().compareTo(g.limits().minOrderUsd()) < 0) {
                    problems.add(s + " 매수 " + l.estAmountUsd() + "달러 < 최소 주문 " + g.limits().minOrderUsd() + "달러");
                }
                if (!bought.add(s)) {
                    problems.add(s + " 매수 줄 중복");
                }
            }
        }
        for (String s : sold) {
            if (bought.contains(s)) {
                problems.add(s + "를 같은 주문안에서 사고팖");
            }
        }
        FxLine fx = p.fx();
        if (fx != null) {
            if (!anyBuy) {
                problems.add("매수 없이 환전");
            }
            if (fx.usd() <= 0) {
                problems.add("환전 금액이 0 이하");
            }
            BigDecimal krw = BigDecimal.valueOf(fx.usd()).multiply(in.fxUsdKrw(), MC).setScale(0, RoundingMode.HALF_UP);
            if (krw.subtract(BigDecimal.valueOf(fx.krwEst())).abs().compareTo(BigDecimal.ONE) > 0) {
                problems.add("예상 원화 " + fx.krwEst() + "원이 환율 계산 " + krw.toPlainString() + "원과 다름");
            }
        }
        return problems.isEmpty()
                ? pass(Rule.ORDER_INTEGRITY, p.lines().size() + "줄 모두 정상")
                : fail(Rule.ORDER_INTEGRITY, String.join("; ", problems));
    }

    private static Check cash(Effect e) {
        List<String> problems = new ArrayList<>();
        if (e.usdAfter().compareTo(CENT.negate()) < 0) {
            problems.add("주문 후 달러 " + money(e.usdAfter()));
        }
        if (e.krwAfter().signum() < 0) {
            problems.add("주문 후 원화 " + e.krwAfter().setScale(0, RoundingMode.HALF_UP).toPlainString() + "원");
        }
        return problems.isEmpty()
                ? pass(Rule.CASH_NON_NEGATIVE, "주문 후 달러 " + money(e.usdAfter()) + ", 원화 "
                        + e.krwAfter().setScale(0, RoundingMode.HALF_UP).toPlainString() + "원")
                : fail(Rule.CASH_NON_NEGATIVE, String.join(", ", problems) + " (돈이 모자람)");
    }

    private static Check size(GuardInput g, Effect e) {
        BigDecimal pct = pct(e.traded().divide(e.totalBefore(), MC));
        BigDecimal cap = g.limits().maxPlanPct();
        return pct.compareTo(cap.add(PCT_TOLERANCE)) <= 0
                ? pass(Rule.SIZE_CAP, "총자산의 " + pct + "% (상한 " + plain(cap) + "%)")
                : fail(Rule.SIZE_CAP, "총자산의 " + pct + "%로 상한 " + plain(cap) + "% 초과");
    }

    /** 상한 아래로 가거나, 이미 넘어 있다면 줄이는 방향이어야 한다. 줄이는 주문을 막으면 영영 못 고친다. */
    private static Check equity(GuardInput g, Effect e) {
        BigDecimal before = pct(e.equityBefore().divide(e.totalBefore(), MC));
        BigDecimal after = pct(e.equityAfter().divide(e.totalAfter(), MC));
        BigDecimal cap = g.limits().equityCapPct();
        String text = "주식 " + before + "% → " + after + "% (상한 " + plain(cap) + "%)";
        if (after.compareTo(cap.add(PCT_TOLERANCE)) <= 0) {
            return pass(Rule.EQUITY_CAP, text);
        }
        if (after.compareTo(before) < 0) {
            return pass(Rule.EQUITY_CAP, text + ", 상한 위지만 줄이는 주문");
        }
        return fail(Rule.EQUITY_CAP, text + ", 상한 초과");
    }

    /** 주문을 그대로 실행했을 때의 결과를 엔진과 별개로 다시 계산한 값. */
    private record Effect(BigDecimal totalBefore, BigDecimal totalAfter, BigDecimal traded,
                          BigDecimal equityBefore, BigDecimal equityAfter,
                          BigDecimal usdAfter, BigDecimal krwAfter) {

        static Effect of(PlanInput in, Plan p, Set<String> equitySymbols) {
            Map<String, Long> held = new HashMap<>();
            in.holdings().forEach((s, q) -> {
                if (in.whitelist().contains(s)) {
                    held.put(s, q);
                }
            });
            Map<String, Long> after = new HashMap<>(held);
            BigDecimal sells = BigDecimal.ZERO;
            BigDecimal buys = BigDecimal.ZERO;
            for (OrderLine l : p.lines()) {
                BigDecimal amt = in.prices().get(l.symbol()).multiply(BigDecimal.valueOf(l.qty()), MC);
                if (l.side() == Side.SELL) {
                    after.merge(l.symbol(), -l.qty(), Long::sum);
                    sells = sells.add(amt, MC);
                } else {
                    after.merge(l.symbol(), l.qty(), Long::sum);
                    buys = buys.add(amt, MC);
                }
            }
            BigDecimal fxUsd = p.fx() == null ? BigDecimal.ZERO : BigDecimal.valueOf(p.fx().usd());
            BigDecimal fxKrw = p.fx() == null ? BigDecimal.ZERO : BigDecimal.valueOf(p.fx().krwEst());
            BigDecimal usdAfter = in.cashUsd().add(sells, MC).subtract(buys, MC).add(fxUsd, MC);
            BigDecimal krwAfter = in.cashKrw().subtract(fxKrw, MC);
            BigDecimal rate = in.fxUsdKrw();
            BigDecimal totalBefore = value(held, in).add(in.cashUsd(), MC).add(in.cashKrw().divide(rate, MC), MC);
            BigDecimal totalAfter = value(after, in).add(usdAfter, MC).add(krwAfter.divide(rate, MC), MC);
            return new Effect(totalBefore, totalAfter, sells.add(buys, MC),
                    equity(held, in, equitySymbols), equity(after, in, equitySymbols), usdAfter, krwAfter);
        }

        private static BigDecimal value(Map<String, Long> qty, PlanInput in) {
            BigDecimal v = BigDecimal.ZERO;
            for (Map.Entry<String, Long> e : qty.entrySet()) {
                v = v.add(in.prices().get(e.getKey()).multiply(BigDecimal.valueOf(e.getValue()), MC), MC);
            }
            return v;
        }

        private static BigDecimal equity(Map<String, Long> qty, PlanInput in, Set<String> equitySymbols) {
            Map<String, Long> only = new HashMap<>();
            qty.forEach((s, q) -> {
                if (equitySymbols.contains(s)) {
                    only.put(s, q);
                }
            });
            return value(only, in);
        }
    }

    // ───────────────────────── 도우미 ─────────────────────────

    /** from 다음 날부터 to까지 평일 수 (한국 영업일 근사: 공휴일은 따지지 않아 더 엄격한 쪽은 아님). */
    static int businessDaysAfter(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    private static Check pass(Rule r, String detail) {
        return new Check(r, true, detail);
    }

    private static Check fail(Rule r, String detail) {
        return new Check(r, false, detail);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio.multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal x) {
        return x.stripTrailingZeros().toPlainString();
    }
}

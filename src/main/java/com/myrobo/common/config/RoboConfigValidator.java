package com.myrobo.common.config;

import com.myrobo.portfolio.RebalancePlanner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 설정 값끼리 맞는지 검사한다. 문제가 하나라도 있으면 앱은 시작하지 않는다.
 * 각 규칙은 "이게 틀리면 실제 돈이 잘못 움직일 수 있는가"를 기준으로 골랐다.
 */
public final class RoboConfigValidator {

    static final Set<String> HORIZONS = Set.of("1M", "3M", "6M", "12M", "36M", "OPEN");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private RoboConfigValidator() {
    }

    public static List<String> validate(RoboConfig c) {
        List<String> p = new ArrayList<>();

        // 목표
        if (!HORIZONS.contains(c.goal().horizon())) {
            p.add("goal.horizon '" + c.goal().horizon() + "'은 " + new TreeSet<>(HORIZONS) + " 중 하나여야 함");
        }
        if (!c.profiles().containsKey(c.goal().horizon())) {
            p.add("goal.horizon '" + c.goal().horizon() + "'에 해당하는 profiles 항목이 없음");
        }
        if (!between(c.goal().maxDrawdownPct(), BigDecimal.ZERO, HUNDRED, false)) {
            p.add("goal.maxDrawdownPct는 0 초과 100 이하여야 함");
        }

        // 프로필
        if (!c.profiles().keySet().containsAll(HORIZONS)) {
            Set<String> missing = new TreeSet<>(HORIZONS);
            missing.removeAll(c.profiles().keySet());
            p.add("profiles에 기간이 빠짐: " + missing);
        }
        Set<String> usedClasses = new TreeSet<>();
        for (Map.Entry<String, Map<String, BigDecimal>> e : c.profiles().entrySet()) {
            String h = e.getKey();
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal equity = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> w : e.getValue().entrySet()) {
                if (w.getValue().signum() < 0) {
                    p.add("profiles." + h + "." + w.getKey() + " 비중이 음수");
                }
                sum = sum.add(w.getValue());
                if (RoboConfig.EQUITY_CLASSES.contains(w.getKey())) {
                    equity = equity.add(w.getValue());
                }
                usedClasses.add(w.getKey());
            }
            if (sum.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0) {
                p.add("profiles." + h + " 비중 합계가 100이 아님 (" + sum.stripTrailingZeros().toPlainString() + ")");
            }
            if (equity.compareTo(c.equityCapPct()) > 0) {
                p.add("profiles." + h + " 주식 비중 " + equity.stripTrailingZeros().toPlainString()
                        + "%가 guard.equityCapPct " + c.equityCapPct().stripTrailingZeros().toPlainString() + "%를 넘음");
            }
        }

        // 종목 매핑과 거래 가능 여부
        usedClasses.remove(RebalancePlanner.SATELLITE);
        for (String assetClass : usedClasses) {
            if (!c.instruments().containsKey(assetClass)) {
                p.add("instruments에 자산군 " + assetClass + "의 종목이 없음");
            }
        }
        Set<String> tradable = c.tradableByBroker().get(c.coreBroker());
        if (tradable == null) {
            p.add("brokerAvailability에 코어 계좌 증권사 " + c.coreBroker() + "의 거래 가능 목록이 없음");
        } else {
            c.instruments().forEach((assetClass, symbol) -> {
                if (!tradable.contains(symbol)) {
                    p.add("instruments." + assetClass + " 종목 " + symbol + "은(는) "
                            + c.coreBroker() + "에서 거래 가능 목록에 없음");
                }
            });
        }

        // 리밸런싱 값 범위
        var r = c.rebalance();
        if (!between(r.bandAbsPct(), BigDecimal.ZERO, HUNDRED, false)) {
            p.add("rebalance.band.absPct는 0 초과 100 이하여야 함");
        }
        if (!between(r.bandRelPct(), BigDecimal.ZERO, HUNDRED, false)) {
            p.add("rebalance.band.relPct는 0 초과 100 이하여야 함");
        }
        if (!between(r.cashBufferPct(), BigDecimal.ZERO, BigDecimal.TEN, true)) {
            p.add("rebalance.cashBufferPct는 0 이상 10 이하여야 함");
        }
        if (r.minOrderUsd().signum() < 0) {
            p.add("rebalance.minOrderUsd는 0 이상이어야 함");
        }
        if (!between(r.maxPlanPct(), BigDecimal.ZERO, HUNDRED, false)) {
            p.add("rebalance.maxPlanPct는 0 초과 100 이하여야 함");
        }
        if (r.initialBuildWeeks() < 1) {
            p.add("rebalance.initialBuildWeeks는 1 이상이어야 함");
        }
        if (!between(c.equityCapPct(), BigDecimal.ZERO, HUNDRED, true)) {
            p.add("guard.equityCapPct는 0 이상 100 이하여야 함");
        }
        RoboConfig.Safety g = c.safety();
        if (g.priceTradingDays() < 1) {
            p.add("guard.dataFreshness.priceTradingDays는 1 이상이어야 함");
        }
        if (g.holdingsDays() < 1) {
            p.add("guard.dataFreshness.holdingsDays는 1 이상이어야 함");
        }
        if (!g.blockNewPlan()) {
            p.add("guard.pendingPlan.blockNewPlan은 1단계에서 끌 수 없음 (같은 주문을 두 번 넣는 사고 방지)");
        }
        if (g.expireTradingDays() < 1) {
            p.add("guard.pendingPlan.expireTradingDays는 1 이상이어야 함");
        }
        if (g.fxMaxAgeBusinessDays() < 1) {
            p.add("dataSources.fx.maxAgeBusinessDays는 1 이상이어야 함");
        }
        return p;
    }

    /** lo < x ≤ hi (includeLo면 lo ≤ x ≤ hi). */
    private static boolean between(BigDecimal x, BigDecimal lo, BigDecimal hi, boolean includeLo) {
        int lower = x.compareTo(lo);
        return (includeLo ? lower >= 0 : lower > 0) && x.compareTo(hi) <= 0;
    }
}

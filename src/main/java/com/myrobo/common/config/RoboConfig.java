package com.myrobo.common.config;

import com.myrobo.portfolio.PlanTypes.PlannerConfig;
import com.myrobo.portfolio.RebalancePlanner;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * robo-config.yml 중 1단계에서 쓰는 부분. 나머지 섹션(위성, 스트레스, 변경 절차 등)은
 * 해당 기능을 만들 때 필드로 옮기고, 그전까지는 raw에 원본 그대로 둔다.
 *
 * @param profiles        기간 → (자산군 → 퍼센트)
 * @param instruments     자산군 → 종목 기호
 * @param tradableByBroker 증권사 → 거래 가능 종목
 */
public record RoboConfig(
        String version,
        ChangeKind changeKind,
        String changeReason,
        Goal goal,
        Map<String, Map<String, BigDecimal>> profiles,
        Map<String, String> instruments,
        String coreBroker,
        Map<String, Set<String>> tradableByBroker,
        Rebalance rebalance,
        BigDecimal equityCapPct,
        Safety safety,
        Map<String, Object> raw) {

    /** 주식으로 보는 자산군. 주식 비중 상한 검사에 쓰인다. */
    public static final Set<String> EQUITY_CLASSES = Set.of("US_STOCK", RebalancePlanner.SATELLITE);

    public enum ChangeKind { BUGFIX, STRICTER, LOOSER, POLICY }

    public record Goal(String horizon, LocalDate startDate, BigDecimal maxDrawdownPct, String baseCurrency) {
    }

    /**
     * 안전 검증의 신선도·미완료 주문안 규칙.
     *
     * @param priceTradingDays      시세가 최근 이 거래일 수 안의 것이어야 함 (2 = 직전 거래일이나 그 전날)
     * @param holdingsDays          잔고 입력이 이 날수보다 오래되면 주문안 중단
     * @param blockNewPlan          미완료 주문안이 있으면 새 주문안을 만들지 않음 (1단계는 반드시 true)
     * @param expireTradingDays     발송된 주문안이 유효한 거래일 수. 지나면 만료
     * @param fxMaxAgeBusinessDays  환율이 이 영업일 수보다 오래되면 주문안 중단
     */
    public record Safety(
            int priceTradingDays,
            int holdingsDays,
            boolean blockNewPlan,
            int expireTradingDays,
            int fxMaxAgeBusinessDays) {
    }

    /** 리밸런싱 설정. 파일에는 퍼센트로 적는다. */
    public record Rebalance(
            BigDecimal bandAbsPct,
            BigDecimal bandRelPct,
            BigDecimal cashBufferPct,
            BigDecimal minOrderUsd,
            BigDecimal maxPlanPct,
            int initialBuildWeeks) {

        public PlannerConfig toPlannerConfig() {
            return new PlannerConfig(
                    ratio(bandAbsPct), ratio(bandRelPct), minOrderUsd, ratio(cashBufferPct), ratio(maxPlanPct));
        }

        private static BigDecimal ratio(BigDecimal pct) {
            return pct.divide(BigDecimal.valueOf(100), MathContext.DECIMAL128);
        }
    }

    /** 코어 계좌가 다루는 종목 (= 리밸런싱 엔진의 화이트리스트). */
    public Set<String> whitelist() {
        return Set.copyOf(instruments.values());
    }

    /**
     * 기간별 프로필을 엔진 입력 형태(종목 기호 → 비율)로 바꾼다. 위성 몫은 SATELLITE 키로 남겨
     * 엔진이 모의 운용 여부에 따라 처리하게 한다.
     */
    public Map<String, BigDecimal> targetsFor(String horizon) {
        Map<String, BigDecimal> profile = profiles.get(horizon);
        if (profile == null) {
            throw new IllegalArgumentException("unknown horizon " + horizon);
        }
        Map<String, BigDecimal> out = new TreeMap<>();
        profile.forEach((assetClass, pct) -> {
            String key = RebalancePlanner.SATELLITE.equals(assetClass) ? assetClass : instruments.get(assetClass);
            out.merge(key, pct.divide(BigDecimal.valueOf(100), MathContext.DECIMAL128), BigDecimal::add);
        });
        return out;
    }

    /** 주식으로 보는 종목 (주식 비중 상한 검사 대상). 위성 몫은 모의 운용 중이라 실제 종목이 없다. */
    public Set<String> equitySymbols() {
        Set<String> out = new java.util.TreeSet<>();
        instruments.forEach((assetClass, symbol) -> {
            if (EQUITY_CLASSES.contains(assetClass)) {
                out.add(symbol);
            }
        });
        return Set.copyOf(out);
    }
}

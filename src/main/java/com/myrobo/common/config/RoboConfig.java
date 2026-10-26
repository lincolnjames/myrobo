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
        Map<String, Object> raw) {

    /** 주식으로 보는 자산군. 주식 비중 상한 검사에 쓰인다. */
    public static final Set<String> EQUITY_CLASSES = Set.of("US_STOCK", RebalancePlanner.SATELLITE);

    public enum ChangeKind { BUGFIX, STRICTER, LOOSER, POLICY }

    public record Goal(String horizon, LocalDate startDate, BigDecimal maxDrawdownPct, String baseCurrency) {
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
}

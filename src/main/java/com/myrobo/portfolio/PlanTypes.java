package com.myrobo.portfolio;

import java.math.BigDecimal;

/**
 * 리밸런싱 엔진의 입출력 타입 모음.
 * 이 패키지는 Spring, DB, 외부 API에 의존하지 않는다 (운영 배치와 백테스트가 같은 코드를 쓰기 위해).
 */
public final class PlanTypes {

    private PlanTypes() {
    }

    /** 주문안 종류. */
    public enum Action { NO_ACTION, BUY_ONLY, FULL, ERROR }

    /** 매매 방향. */
    public enum Side { BUY, SELL }

    /** 평소 / 초기 구축 / 프로필 전환. 뒤의 둘은 주문안 규모 상한에 맞춰 비율대로 축소한다. */
    public enum Mode { NORMAL, INITIAL_BUILD, PROFILE_SHIFT }

    /** 주문 줄 하나. 금액은 예상 체결가 기준 달러. */
    public record OrderLine(Side side, String symbol, long qty, BigDecimal estAmountUsd) {
    }

    /** 원화 → 달러 환전 줄. usd는 정수 달러, krwEst는 예상 원화. */
    public record FxLine(long usd, long krwEst) {
    }

    /**
     * 엔진 설정. 모든 값은 비율(0.05 = 5%)이다.
     * robo-config.yml은 퍼센트로 적으므로 설정 로딩 단계에서 100으로 나눠 넘긴다.
     */
    public record PlannerConfig(
            BigDecimal bandAbs,
            BigDecimal bandRel,
            BigDecimal minOrderUsd,
            BigDecimal cashBufferPct,
            BigDecimal maxPlanPct) {

        public static PlannerConfig defaults() {
            return new PlannerConfig(
                    new BigDecimal("0.05"),
                    new BigDecimal("0.25"),
                    new BigDecimal("50"),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.25"));
        }
    }

    /** 계산 전에 입력이 잘못된 경우. code는 출력의 reason으로 그대로 나간다. */
    public static final class PlanException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String detail;

        public PlanException(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
            this.detail = detail;
        }

        public String code() {
            return code;
        }

        public String detail() {
            return detail;
        }
    }
}

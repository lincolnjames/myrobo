package com.myrobo.plan;

import com.myrobo.guard.SafetyGuard;
import com.myrobo.holdings.HoldingsService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 주문안 API 응답 타입. */
public final class PlanViews {

    private PlanViews() {
    }

    public record CheckView(String rule, String label, boolean passed, String detail) {

        /** 이번 계산의 안전 검증 결과를 응답 형태로. 만들기와 미리보기가 같이 쓴다. */
        public static List<CheckView> of(SafetyGuard.Report report) {
            return report.checks().stream()
                    .map(x -> new CheckView(x.rule().name(), x.label(), x.passed(), x.detail())).toList();
        }
    }

    public record ItemView(long id, String side, String symbol, long qty, BigDecimal estPrice, BigDecimal estAmount) {
    }

    public record PlanView(
            long id,
            LocalDate tradeDate,
            String type,
            String status,
            String action,
            String reason,
            String note,
            BigDecimal totalUsd,
            BigDecimal sizePct,
            Long fxUsd,
            Long fxKrwEst,
            LocalDate expiresAt,
            OffsetDateTime createdAt,
            List<ItemView> items,
            List<CheckView> checks) {
    }

    /** 주문안 만들기 결과. */
    public enum Outcome {
        /** 검증 통과, 발송(화면 표시)됨. 이 주문안대로 주문하면 된다 */
        SENT,
        /** 검증 실패, 폐기됨. 주문하지 않는다 */
        REJECTED,
        /** 오늘 할 일 없음 (저장하지 않음) */
        NO_ACTION,
        /** 미완료 주문안이 있어 새로 만들지 않음 */
        OPEN_EXISTS,
        /** 오늘 주문안이 이미 있음 (하루 1건) */
        ALREADY_TODAY
    }

    /**
     * @param plan   저장된 주문안 (NO_ACTION이면 null)
     * @param checks 이번 계산의 안전 검증 결과 (OPEN_EXISTS, ALREADY_TODAY면 기존 주문안의 것)
     */
    public record CreateResult(Outcome outcome, String message, PlanView plan, List<CheckView> checks) {
    }

    public record Overview(TradingSchedule schedule, PlanView open, List<PlanView> recent) {
    }

    /** 미리보기 주문 줄. 금액은 예상 체결가(직전 종가) 기준 달러. */
    public record PreviewLine(String side, String symbol, long qty, BigDecimal estPrice, BigDecimal estAmountUsd) {
    }

    /**
     * 종목별 비중(퍼센트). 계산할 수 없으면 null.
     *
     * <p>현재·목표 비중과 breachBefore는 잔고 화면과 같은 {@code Valuation}에서, afterPct·breachAfter는 엔진 결과에서
     * 온다. 둘은 같은 규칙(종목 집합 = 목표 종목 + 보유 중인 화이트리스트 종목, 버퍼·허용 범위 계산)을 쓰므로
     * 엔진이 돌았으면 모든 종목에 afterPct가 있고, 매매가 없을 때 afterPct = currentPct다 (테스트로 확인).
     *
     * @param afterPct    이 주문안대로 체결되면 될 비중. 엔진이 계산하지 않았으면(분할 대기·오류) null
     * @param breachAfter 체결 뒤에도 허용 범위 밖인가. afterPct가 null이면 null
     */
    public record WeightView(String symbol, long qty, BigDecimal currentPct, BigDecimal targetPct,
                             BigDecimal bandPct, BigDecimal afterPct, boolean breachBefore, Boolean breachAfter) {
    }

    /**
     * 주문안 미리보기 (저장하지 않음). 지금 주문안을 만들면 나올 결과와 그 계산.
     *
     * @param predicted      지금 {@code POST /api/plans}를 누르면 나올 결과
     * @param kind           CORE / INITIAL_BUILD(나눠 내는 큰 주문안)
     * @param excluded       계산에서 뺀 시스템 밖 종목 (엔진 기준. 엔진이 못 돌았으면 잔고 평가 기준)
     * @param checks         만들기와 같은 입력으로 돌린 안전 검증. 미완료 주문안이 있으면 NO_OPEN_PLAN이 실패로 나온다
     * @param openPlanId     만들기 때 남아 있을 미완료 주문안. 없으면 null
     * @param expiringPlanId 기한이 지나 만들기 때 먼저 만료될 주문안. 없으면 null
     */
    public record PreviewResult(
            Outcome predicted,
            String message,
            TradingSchedule schedule,
            String kind,
            String action,
            String reason,
            String note,
            BigDecimal totalUsd,
            BigDecimal sizePct,
            Long fxUsd,
            Long fxKrwEst,
            List<PreviewLine> lines,
            List<WeightView> weights,
            BigDecimal cashCurrentPct,
            BigDecimal cashTargetPct,
            BigDecimal cashAfterUsd,
            BigDecimal cashAfterKrw,
            List<String> excluded,
            boolean guardPassed,
            List<CheckView> checks,
            Long openPlanId,
            Long expiringPlanId) {
    }

    /**
     * 환전 예상 대비 실제 환율. 주문안에 환전이 없었으면 expectedRate, diffPct는 null.
     *
     * @param diffPct 예상보다 비싸게 환전했으면 양수 (%)
     */
    public record FxCheck(BigDecimal expectedRate, BigDecimal actualRate, BigDecimal diffPct) {
    }

    /** 체결 입력 결과. */
    public enum ExecutionStatus {
        /** 저장됨: 주문안 DONE, 잔고 갱신 */
        SAVED,
        /** 입력이 잘못돼 아무것도 저장하지 않음 */
        INVALID,
        /** 지금 체결을 받을 수 없는 상태 (SENT가 아니거나 이중 반영 위험) */
        CONFLICT
    }

    /**
     * @param errors    INVALID, CONFLICT일 때 이유 목록
     * @param fx        SAVED이고 환전을 입력했을 때만
     * @param portfolio SAVED일 때 갱신된 잔고 화면 상태
     */
    public record ExecutionResult(ExecutionStatus status, String message, List<String> errors, FxCheck fx,
                                  HoldingsService.PortfolioResponse portfolio) {
    }
}

package com.myrobo.plan;

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

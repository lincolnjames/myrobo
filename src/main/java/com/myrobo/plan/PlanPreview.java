package com.myrobo.plan;

import com.myrobo.guard.SafetyGuard.Report;
import com.myrobo.plan.PlanDecider.Decision;
import com.myrobo.plan.PlanViews.CheckView;
import com.myrobo.plan.PlanViews.Outcome;
import com.myrobo.plan.PlanViews.PreviewLine;
import com.myrobo.plan.PlanViews.PreviewResult;
import com.myrobo.plan.PlanViews.WeightView;
import com.myrobo.portfolio.Plan;
import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.Valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * 주문안 미리보기 응답 조립. 순수 함수 (DB·시계 없음).
 *
 * <p>예상 결과는 만들기와 같은 {@link PlanService#decideOutcome}으로 정한다.
 * 미완료 주문안이 있거나 오늘 이미 만들었어도 계산 결과는 참고용으로 그대로 보여 준다.
 */
public final class PlanPreview {

    static final String PREFIX = "저장하지 않은 미리보기예요. ";
    static final String DO_NOT_ORDER = " 이 미리보기로 주문하지 마세요.";

    private PlanPreview() {
    }

    /**
     * @param report         만들기와 같은 입력으로 돌린 안전 검증 (미완료 주문안·만료될 주문안 반영)
     * @param view           지금 잔고의 비중 평가 (잔고 화면과 같은 계산)
     * @param openPlanId     만들기 때 남아 있을 미완료 주문안. 없으면 null
     * @param expiringPlanId 기한이 지나 만들기 때 먼저 만료될 주문안. 없으면 null
     * @param todayPlanId    같은 거래일·규칙 버전으로 이미 만든 주문안. 없으면 null
     */
    public static PreviewResult of(Decision d, Report report, TradingSchedule sched, Valuation.View view,
                                   Long openPlanId, Long expiringPlanId, Long todayPlanId, LocalDate goalStart) {
        Plan plan = d.plan();
        Outcome predicted = PlanService.decideOutcome(openPlanId, plan, report, todayPlanId);
        String message = switch (predicted) {
            case OPEN_EXISTS -> "미완료 주문안 #" + openPlanId + "이 있어 지금 만들면 새로 만들어지지 않아요."
                    + " 아래 계산은 참고용이에요.";
            case NO_ACTION -> PlanService.noActionMessage(plan, report, goalStart);
            case ALREADY_TODAY -> sched.tradeDate() + " 주문안은 이미 있어요 (#" + todayPlanId
                    + "). 하루에 한 건만 만들어서, 아래 계산은 참고용이에요.";
            case SENT -> "지금 주문안을 만들면 이대로 발송돼요. 주문은 주문안을 만든 뒤 그 주문안대로 하세요.";
            case REJECTED -> "지금 만들면 안전 검증에 걸려 폐기돼요: " + PlanService.failedLabels(report) + ".";
        };
        if (expiringPlanId != null) {
            message = "주문안 #" + expiringPlanId + "은 기한이 지나 만들 때 먼저 만료돼요. " + message;
        }
        if (plan.action() != Action.NO_ACTION) {
            message += DO_NOT_ORDER;
        }

        Map<String, BigDecimal> prices = d.input() == null ? Map.of() : d.input().prices();
        List<PreviewLine> lines = plan.lines().stream()
                .map(l -> new PreviewLine(l.side().name(), l.symbol(), l.qty(), prices.get(l.symbol()),
                        l.estAmountUsd()))
                .toList();

        // 현재 비중은 Valuation, 매매 뒤 비중은 엔진. 같은 규칙이라 종목 집합이 같다 (WeightView 참고).
        SortedMap<String, BigDecimal> after = plan.weightsAfterPct();
        List<WeightView> weights = view.rows().stream()
                .map(r -> {
                    BigDecimal a = after == null ? null : after.get(r.symbol());
                    return new WeightView(r.symbol(), r.qty(), r.weightPct(), r.targetPct(), r.bandPct(), a,
                            r.breach(), a == null ? null : plan.breachesAfter().contains(r.symbol()));
                })
                .toList();

        // 엔진이 실제로 뺀 종목. 엔진이 못 돌았으면(오류) 엔진 목록이 비어 있으니 잔고 평가 기준으로 보여 준다.
        List<String> excluded = plan.action() == Action.ERROR
                ? view.excluded().stream().map(Valuation.Position::symbol).toList()
                : plan.excludedHoldings();

        return new PreviewResult(predicted, PREFIX + message, sched, d.kind().name(),
                plan.action().name(), plan.reason(), d.note() != null ? d.note() : plan.detail(),
                plan.totalUsd(), plan.sizePct(),
                plan.fx() == null ? null : plan.fx().usd(), plan.fx() == null ? null : plan.fx().krwEst(),
                lines, weights, view.cashWeightPct(), view.cashTargetPct(),
                plan.cashAfterUsd(), plan.cashAfterKrw(), excluded,
                report.passed(), CheckView.of(report), openPlanId, expiringPlanId);
    }
}

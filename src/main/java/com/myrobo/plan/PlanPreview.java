package com.myrobo.plan;

import com.myrobo.guard.SafetyGuard;
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
 * <p>결과 판정 순서는 {@link PlanService#create()}와 같다: 미완료 주문안 → 주문 없음 → 오늘 이미 있음 → 검증 통과/실패.
 * 미완료 주문안이 있거나 오늘 이미 만들었어도 계산 결과는 참고용으로 그대로 보여 준다.
 */
public final class PlanPreview {

    static final String PREFIX = "저장하지 않은 미리보기예요. ";
    static final String DO_NOT_ORDER = " 이 미리보기로 주문하지 마세요.";

    private PlanPreview() {
    }

    /**
     * @param view        지금 잔고의 비중 평가 (잔고 화면과 같은 계산)
     * @param openPlanId  미완료 주문안. 없으면 null
     * @param todayPlanId 같은 거래일·규칙 버전으로 이미 만든 주문안. 없으면 null
     */
    public static PreviewResult of(Decision d, Report report, TradingSchedule sched, Valuation.View view,
                                   Long openPlanId, Long todayPlanId, LocalDate goalStart) {
        Plan plan = d.plan();
        Outcome predicted;
        String message;
        if (openPlanId != null) {
            predicted = Outcome.OPEN_EXISTS;
            message = "미완료 주문안 #" + openPlanId + "이 있어 지금 만들면 새로 만들어지지 않아요"
                    + " (기한이 지났다면 만들 때 먼저 만료 처리돼요). 아래 계산은 참고용이에요.";
        } else if (plan.action() == Action.NO_ACTION) {
            predicted = Outcome.NO_ACTION;
            message = PlanService.noActionMessage(plan, report, goalStart);
        } else if (report.passed() && todayPlanId != null) {
            predicted = Outcome.ALREADY_TODAY;
            message = sched.tradeDate() + " 주문안은 이미 있어요 (#" + todayPlanId
                    + "). 하루에 한 건만 만들어서, 아래 계산은 참고용이에요.";
        } else if (report.passed()) {
            predicted = Outcome.SENT;
            message = "지금 주문안을 만들면 이대로 발송돼요. 주문은 주문안을 만든 뒤 그 주문안대로 하세요.";
        } else {
            predicted = Outcome.REJECTED;
            message = "지금 만들면 안전 검증에 걸려 폐기돼요: " + String.join(", ",
                    report.failures().stream().map(SafetyGuard.Check::label).toList()) + ".";
        }
        if (plan.action() != Action.NO_ACTION) {
            message += DO_NOT_ORDER;
        }

        Map<String, BigDecimal> prices = d.input() == null ? Map.of() : d.input().prices();
        List<PreviewLine> lines = plan.lines().stream()
                .map(l -> new PreviewLine(l.side().name(), l.symbol(), l.qty(), prices.get(l.symbol()),
                        l.estAmountUsd()))
                .toList();

        SortedMap<String, BigDecimal> after = plan.weightsAfterPct();
        List<WeightView> weights = view.rows().stream()
                .map(r -> {
                    BigDecimal a = after == null ? null : after.get(r.symbol());
                    return new WeightView(r.symbol(), r.qty(), r.weightPct(), r.targetPct(), r.bandPct(), a,
                            r.breach(), a == null ? null : plan.breachesAfter().contains(r.symbol()));
                })
                .toList();

        List<CheckView> checks = report.checks().stream()
                .map(x -> new CheckView(x.rule().name(), x.label(), x.passed(), x.detail())).toList();
        List<String> excluded = view.excluded().stream().map(Valuation.Position::symbol).toList();

        return new PreviewResult(predicted, PREFIX + message, sched, d.kind().name(),
                plan.action().name(), plan.reason(), d.note() != null ? d.note() : plan.detail(),
                plan.totalUsd(), plan.sizePct(),
                plan.fx() == null ? null : plan.fx().usd(), plan.fx() == null ? null : plan.fx().krwEst(),
                lines, weights, view.cashWeightPct(), view.cashTargetPct(),
                plan.cashAfterUsd(), plan.cashAfterKrw(), excluded,
                report.passed(), checks, openPlanId);
    }
}

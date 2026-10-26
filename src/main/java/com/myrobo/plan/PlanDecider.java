package com.myrobo.plan;

import com.myrobo.portfolio.Plan;
import com.myrobo.portfolio.PlanInput;
import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.PlanTypes.Mode;
import com.myrobo.portfolio.RebalancePlanner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 오늘 어떤 주문안을 낼지 정한다. 순수 함수.
 *
 * <p>규칙: 평소 방식으로 계산한 주문안이 크기 상한(총자산의 25%)을 넘으면 한 번에 내지 않고,
 * 매주 첫 거래일에 상한만큼씩 나눠 낸다 (엔진의 축소 모드). 처음 포트폴리오를 만들 때나 큰돈을 넣었을 때,
 * 큰 폭락 뒤처럼 매매가 많이 필요한 경우가 모두 여기에 해당한다. 그래서 어떤 주문안도 상한을 넘지 않는다.
 */
public final class PlanDecider {

    /** 주문안 종류 (order_plan.type). */
    public enum PlanKind { CORE, INITIAL_BUILD }

    /**
     * @param plan  엔진 결과. 나눠 내는 날을 기다리는 중이면 action = NO_ACTION, reason = SPLIT_WAIT
     * @param input 실제로 계산에 쓴 입력 (축소 모드면 mode가 바뀌어 있음)
     * @param note  화면에 보여 줄 설명. 없으면 null
     */
    public record Decision(Plan plan, PlanInput input, PlanKind kind, String note) {
    }

    public static final String SPLIT_WAIT = "SPLIT_WAIT";

    private PlanDecider() {
    }

    /**
     * @param normal      평소 방식 입력 (mode = NORMAL, 정기 점검일 여부 포함)
     * @param splitDay    나눠 내는 날(매주 첫 거래일)인가
     * @param nextSplitDay 다음 나눠 내는 날 (안내용)
     * @param maxPlanPct  크기 상한 (퍼센트)
     */
    public static Decision decide(RebalancePlanner planner, PlanInput normal, boolean splitDay,
                                  LocalDate nextSplitDay, BigDecimal maxPlanPct) {
        if (normal.mode() != Mode.NORMAL) {
            throw new IllegalArgumentException("평소 방식 입력이 필요함: " + normal.mode());
        }
        Plan p = planner.plan(normal);
        if (p.action() == Action.ERROR || p.action() == Action.NO_ACTION
                || p.sizePct().compareTo(maxPlanPct) <= 0) {
            return new Decision(p, normal, PlanKind.CORE, null);
        }
        String need = "필요한 매매가 총자산의 " + p.sizePct() + "%라 " + maxPlanPct.stripTrailingZeros().toPlainString()
                + "%씩 매주 나눠 진행";
        if (!splitDay) {
            Plan wait = new Plan(Action.NO_ACTION, SPLIT_WAIT, need + ". 다음 분할 주문일: " + nextSplitDay,
                    List.of(), null, BigDecimal.ZERO.setScale(2), null, p.totalUsd(),
                    p.breachesBefore(), p.breachesBefore(), null, null, null, p.excludedHoldings());
            return new Decision(wait, normal, PlanKind.CORE, wait.detail());
        }
        PlanInput scaled = new PlanInput(normal.asOf(), normal.rebalanceDay(), Mode.INITIAL_BUILD,
                normal.satellitePaper(), normal.targets(), normal.whitelist(), normal.prices(), normal.holdings(),
                normal.cashUsd(), normal.cashKrw(), normal.fxUsdKrw());
        return new Decision(planner.plan(scaled), scaled, PlanKind.INITIAL_BUILD, need);
    }
}

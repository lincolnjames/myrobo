package com.myrobo.portfolio;

import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.PlanTypes.FxLine;
import com.myrobo.portfolio.PlanTypes.OrderLine;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;

/**
 * 리밸런싱 계산 결과 (주문안 초안). 안전 검증은 이 다음 단계에서 따로 한다.
 *
 * @param reason 결정 이유. action이 ERROR면 오류 코드
 * @param fx     환전이 필요 없으면 null
 */
public record Plan(
        Action action,
        String reason,
        String detail,
        List<OrderLine> lines,
        FxLine fx,
        BigDecimal sizePct,
        BigDecimal scaleFactor,
        BigDecimal totalUsd,
        List<String> breachesBefore,
        List<String> breachesAfter,
        SortedMap<String, BigDecimal> weightsAfterPct,
        BigDecimal cashAfterUsd,
        BigDecimal cashAfterKrw,
        List<String> excludedHoldings) {

    public static Plan error(String code, String detail) {
        return new Plan(Action.ERROR, code, detail, List.of(), null,
                null, null, null, List.of(), List.of(), null, null, null, List.of());
    }
}

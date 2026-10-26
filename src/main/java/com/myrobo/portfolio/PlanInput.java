package com.myrobo.portfolio;

import com.myrobo.portfolio.PlanTypes.Mode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 리밸런싱 계산 입력. 종목 기호 기준이다 (자산군 → 종목 변환은 호출하는 쪽에서).
 *
 * @param targets   프로필 비중(합계 1). "SATELLITE" 키는 위성 몫
 * @param whitelist 시스템이 다루는 종목. 밖의 보유 종목은 계산에서 제외
 * @param prices    종목별 달러 가격 (직전 종가)
 * @param holdings  종목별 보유 주수 (화이트리스트 밖 종목 포함 가능)
 */
public record PlanInput(
        LocalDate asOf,
        boolean rebalanceDay,
        Mode mode,
        boolean satellitePaper,
        Map<String, BigDecimal> targets,
        Set<String> whitelist,
        Map<String, BigDecimal> prices,
        Map<String, Long> holdings,
        BigDecimal cashUsd,
        BigDecimal cashKrw,
        BigDecimal fxUsdKrw) {

    public PlanInput {
        Objects.requireNonNull(mode, "mode");
        targets = Map.copyOf(targets);
        whitelist = Set.copyOf(whitelist);
        prices = Map.copyOf(prices);
        holdings = Map.copyOf(holdings);
        Objects.requireNonNull(cashUsd, "cashUsd");
        Objects.requireNonNull(cashKrw, "cashKrw");
        Objects.requireNonNull(fxUsdKrw, "fxUsdKrw");
    }
}

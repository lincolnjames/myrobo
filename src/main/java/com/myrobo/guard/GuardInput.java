package com.myrobo.guard;

import com.myrobo.portfolio.Plan;
import com.myrobo.portfolio.PlanInput;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 안전 검증에 필요한 사실 전부. 검증은 이 값만 보고 판단하며 DB·시계·달력을 직접 보지 않는다.
 *
 * @param tradeDate           주문을 넣을 미국 거래일
 * @param todayKst            검증하는 날 (한국 날짜)
 * @param snapshotAsOf        마지막 잔고 기준일. 없으면 null
 * @param snapshotEnteredAt   그 잔고를 저장한 시각. 없으면 null
 * @param lastClosedPlanAt    마지막으로 완료·만료된 주문안이 닫힌 시각. 없으면 null
 * @param openPlanId          발송 후 아직 닫히지 않은 주문안. 없으면 null
 * @param priceRequired       시세가 있어야 하는 종목 (목표 종목 + 보유 중인 화이트리스트 종목)
 * @param priceLag            종목 → 마지막 시세가 기대 거래일보다 몇 거래일 뒤처졌는지 (0 = 최신)
 * @param fxDate              마지막 환율 날짜. 없으면 null
 * @param input               엔진 입력. 잔고나 환율이 없어 만들 수 없었으면 null
 * @param plan                엔진 결과 (계산 실패면 action = ERROR)
 * @param equitySymbols       주식으로 보는 종목
 */
public record GuardInput(
        LocalDate tradeDate,
        LocalDate todayKst,
        LocalDate goalStart,
        LocalDate snapshotAsOf,
        Instant snapshotEnteredAt,
        Instant lastClosedPlanAt,
        Long openPlanId,
        Set<String> priceRequired,
        Map<String, Integer> priceLag,
        LocalDate fxDate,
        PlanInput input,
        Plan plan,
        Set<String> equitySymbols,
        Limits limits) {

    /**
     * 설정 파일에서 온 한도. 비율은 퍼센트(25 = 25%).
     *
     * @param priceTradingDays 시세가 최근 이 거래일 수 안의 것이어야 함 (2면 뒤처짐 1거래일까지 허용)
     */
    public record Limits(
            int priceTradingDays,
            int holdingsDays,
            int fxMaxAgeBusinessDays,
            BigDecimal maxPlanPct,
            BigDecimal equityCapPct,
            BigDecimal minOrderUsd) {
    }

    public GuardInput {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(todayKst, "todayKst");
        Objects.requireNonNull(goalStart, "goalStart");
        priceRequired = Set.copyOf(priceRequired);
        priceLag = Map.copyOf(priceLag);
        Objects.requireNonNull(plan, "plan");
        equitySymbols = Set.copyOf(equitySymbols);
        Objects.requireNonNull(limits, "limits");
    }
}

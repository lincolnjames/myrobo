/**
 * 주문안 만들기·저장·상태 관리.
 *
 * <p>흐름: 주문 일정({@link com.myrobo.plan.TradingSchedule}) → 오늘 낼 주문안 결정({@link com.myrobo.plan.PlanDecider},
 * 상한을 넘는 큰 주문안은 매주 첫 거래일에 25%씩 분할) → 안전 검증 → DRAFT 저장 → 통과하면 SENT, 아니면 REJECTED.
 * SENT 주문안은 체결 입력(화면, 또는 증권사 체결내역으로 자동)으로 DONE이 되거나 3거래일이 지나면 EXPIRED가 된다.
 * 증권사에 접수된 주문이 아직 체결 반영 전이면 하루(거래일) 더 기다렸다가 만료한다. 그 전에는 새 주문안을 만들지 않는다.
 */
package com.myrobo.plan;

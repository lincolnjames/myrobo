/**
 * 주문안 만들기·저장·상태 관리.
 *
 * <p>흐름: 주문 일정({@link com.myrobo.plan.TradingSchedule}) → 오늘 낼 주문안 결정({@link com.myrobo.plan.PlanDecider},
 * 상한을 넘는 큰 주문안은 매주 첫 거래일에 25%씩 분할) → 안전 검증 → DRAFT 저장 → 통과하면 SENT, 아니면 REJECTED.
 * SENT 주문안은 체결 입력으로 DONE이 되거나 3거래일이 지나면 EXPIRED가 된다. 그 전에는 새 주문안을 만들지 않는다.
 * 체결 입력과 텔레그램 알림은 다음 작업.
 */
package com.myrobo.plan;

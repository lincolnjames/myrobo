/**
 * 증권사 API 연동 (2단계). 한국투자증권(KIS Developers) 해외주식 예약주문으로 주문안을 보낸다.
 *
 * <p>{@link com.myrobo.broker.OrderPlanner}(주문 줄·지정가·한도)와 {@link com.myrobo.broker.ReservationWindow}(접수 시간)는
 * 순수 계산이다. {@link com.myrobo.broker.KisClient}는 HTTP 요청 형식만 알고, 판단은
 * {@link com.myrobo.broker.OrderDispatcher}가 한다. 지금은 모의투자(PAPER)와 드라이런만 지원한다.
 */
package com.myrobo.broker;

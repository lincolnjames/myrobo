/**
 * 외부 데이터 수집: Tiingo 일봉·분할·배당, Finnhub 휴장일, 수출입은행·Frankfurter 환율.
 *
 * <p>하위 패키지: {@code http}(요청·재시도·호출 한도), {@code source}(API별 클라이언트·응답 해석),
 * {@code store}(Postgres 저장). 이 패키지의 수집 로직은 Spring을 모르며 {@link com.myrobo.collector.CollectorConfiguration}에서만 엮는다.
 */
package com.myrobo.collector;

/**
 * 일별 평가액과 낙폭: 매일 작업에서 수집 뒤 하루 한 번 평가액을 기록하고, 입출금·매매 영향을 뺀 지수의
 * 고점 대비 하락이 설정의 경보선을 넘으면 알린다. 계산(PeriodReturn, Drawdown)은 DB를 모르는 순수 함수다.
 */
package com.myrobo.valuation;

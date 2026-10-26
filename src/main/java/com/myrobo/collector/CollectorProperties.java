package com.myrobo.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * 수집 설정 (application.yml의 robo.collector). 투자 규칙이 아니라 실행 환경 설정이라
 * 승인 절차가 걸린 robo-config.yml이 아니라 여기에 둔다. API 키는 환경변수로만 받는다.
 */
@ConfigurationProperties("robo.collector")
public record CollectorProperties(
        boolean enabled,
        String cron,
        LocalDate backfillStart,
        Tiingo tiingo,
        Endpoint finnhub,
        Endpoint koreaexim,
        Endpoint frankfurter) {

    public record Tiingo(String baseUrl, String key, int perHour, int perDay) {
    }

    public record Endpoint(String baseUrl, String key) {
    }
}

package com.myrobo.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 증권사 전송 설정 (application.yml의 robo.broker). 키·계좌번호는 환경변수로만 받는다.
 *
 * <p>한도(maxOrderUsd, maxDailyUsd)는 지금 모의투자 시험용이다. 실전으로 넘어갈 때 robo-config.yml(승인 대상)로 옮긴다.
 *
 * @param mode off | dry-run | paper
 */
@ConfigurationProperties("robo.broker")
public record BrokerProperties(
        String mode,
        Kis kis,
        BigDecimal limitBufferPct,
        BigDecimal maxOrderUsd,
        BigDecimal maxDailyUsd,
        Map<String, String> exchanges) {

    /** @param env 키가 어느 계좌 것인지 (paper | live). 지금은 paper만 받는다 */
    public record Kis(String appKey, String appSecret, String account, String env) {
    }
}

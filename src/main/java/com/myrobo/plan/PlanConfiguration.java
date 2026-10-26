package com.myrobo.plan;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 주문안 모듈 설정. 시계를 빈으로 두어 테스트에서 날짜를 고정할 수 있게 한다. */
@Configuration
public class PlanConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

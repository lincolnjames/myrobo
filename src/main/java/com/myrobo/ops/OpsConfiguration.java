package com.myrobo.ops;

import com.myrobo.collector.CollectorJob;
import com.myrobo.collector.CollectorProperties;
import com.myrobo.plan.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 운영 모듈 조립: 알림 통로와 매일 작업. 클래스들은 Spring을 모르고 여기서만 엮는다. */
@Configuration
public class OpsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpsConfiguration.class);

    /** 토큰과 chat id가 둘 다 있을 때만 텔레그램으로 보낸다. */
    @Bean
    Notifier notifier(@Value("${robo.notify.telegram.token:}") String token,
                      @Value("${robo.notify.telegram.chat-id:}") String chatId) {
        if (token.isBlank() || chatId.isBlank()) {
            log.info("알림 꺼짐: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID가 설정되지 않음");
            return new NoopNotifier();
        }
        log.info("텔레그램 알림 켜짐");
        return new TelegramNotifier(new JdkTelegramSender(token, chatId), token);
    }

    @Bean
    DailyJob dailyJob(CollectorJob collector, PlanService plans, JobRuns jobRuns, Notifier notifier,
                      CollectorProperties collectorProps, @Value("${robo.daily.auto-plan:true}") boolean autoPlan) {
        return new DailyJob(collector::run, plans::create, jobRuns, notifier,
                new DailyJob.Settings(collectorProps.enabled(), autoPlan));
    }
}

package com.myrobo.ops;

import com.myrobo.collector.CollectorJob;
import com.myrobo.collector.CollectorProperties;
import com.myrobo.plan.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;

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

    /** HEALTHCHECK_URL이 있을 때만 외부 감시 서비스에 완료 신호를 보낸다. URL에 비밀 키가 들어 있다. */
    @Bean
    Heartbeat dailyHeartbeat(@Value("${robo.notify.healthcheck-url:}") String url) {
        if (url.isBlank()) {
            log.info("완료 신호 꺼짐: HEALTHCHECK_URL이 설정되지 않음");
            return new NoopHeartbeat();
        }
        log.info("완료 신호 켜짐");
        return new JdkHeartbeat(url);
    }

    /** 백업은 주 1회라 매일 작업과 다른 체크(주기 7일)를 쓴다. */
    @Bean
    Heartbeat backupHeartbeat(@Value("${robo.notify.healthcheck-backup-url:}") String url) {
        if (url.isBlank()) {
            log.info("백업 완료 신호 꺼짐: HEALTHCHECK_BACKUP_URL이 설정되지 않음");
            return new NoopHeartbeat();
        }
        log.info("백업 완료 신호 켜짐");
        return new JdkHeartbeat(url);
    }

    @Bean
    DailyJob dailyJob(CollectorJob collector, PlanService plans, JobRuns jobRuns, Notifier notifier,
                      @Qualifier("dailyHeartbeat") Heartbeat heartbeat, CollectorProperties collectorProps,
                      @Value("${robo.daily.auto-plan:true}") boolean autoPlan) {
        return new DailyJob(collector::run, plans::create, jobRuns, notifier,
                new DailyJob.Settings(collectorProps.enabled(), autoPlan), heartbeat);
    }

    @Bean
    PgBackupStore pgBackupStore(DataSource dataSource) {
        return new PgBackupStore(dataSource);
    }

    @Bean
    BackupJob backupJob(PgBackupStore store, JobRuns jobRuns, Notifier notifier,
                        @Qualifier("backupHeartbeat") Heartbeat heartbeat,
                        @Value("${robo.backup.enabled:true}") boolean enabled,
                        @Value("${robo.backup.dir}") String dir, @Value("${robo.backup.keep:8}") int keep) {
        var service = new BackupService(store, Path.of(dir), Clock.systemUTC(), keep);
        log.info("백업 폴더: {} (최근 {}개 보관)", Path.of(dir).toAbsolutePath(), keep);
        return new BackupJob(service::run, jobRuns, notifier, heartbeat, enabled);
    }

    @Bean
    BackupRestore backupRestore(PgBackupStore store, @Value("${robo.backup.dir}") String dir) {
        return new BackupRestore(store, Path.of(dir));
    }

    /**
     * 기동하고 잠시 뒤, 오늘 몫을 놓쳤으면 한 번 따라잡는다. 수집·자동 주문안이 꺼져 있으면 매일 작업은 하지 않는다.
     * 백업은 최근 성공이 없을 때 따라잡는다. 둘은 서로 영향을 주지 않도록 따로 감싼다.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> catchUpOnStartup(
            DailyJob daily, BackupJob backup, JobRuns jobRuns, CollectorProperties collectorProps,
            @Value("${robo.daily.auto-plan:true}") boolean autoPlan,
            @Value("${robo.daily.catch-up:true}") boolean catchUp,
            @Value("${robo.daily.catch-up-after:08:10}") LocalTime after,
            @Value("${robo.daily.catch-up-delay-seconds:60}") long delaySeconds) {
        var task = new CatchUp(daily::run, jobRuns, Clock.systemUTC(), after,
                catchUp && autoPlan && collectorProps.enabled());
        return event -> {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delaySeconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    task.runIfMissed();
                } catch (RuntimeException e) {
                    log.error("놓친 작업 따라잡기 실패", e);
                }
                try {
                    backup.catchUpIfStale(Clock.systemUTC());
                } catch (RuntimeException e) {
                    log.error("놓친 백업 따라잡기 실패", e);
                }
            }, "catch-up");
            t.setDaemon(true);
            t.start();
        };
    }
}

package com.myrobo.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 매주 일요일 09:00(KST) 백업. 결과는 job_run에 남기고, 실패하면 텔레그램으로 알린다 (성공은 조용하다).
 * 끝나면 외부 감시 서비스에 별도 완료 신호를 보내, 일주일 넘게 끊기면 그쪽에서 알려 준다.
 */
public class BackupJob {

    public static final String JOB = "backup-weekly";
    /** 주 1회 주기에 하루 여유를 둔 값. 이보다 오래 성공이 없으면 앱을 켤 때 따라잡는다. */
    static final Duration STALE_AFTER = Duration.ofDays(8);
    private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

    /** 수동 실행 응답용 요약. */
    public record Summary(String status, String file, long rows) {
    }

    private final Supplier<BackupService.Result> backup;
    private final JobRuns jobRuns;
    private final Notifier notifier;
    private final Heartbeat heartbeat;
    private final boolean enabled;

    public BackupJob(Supplier<BackupService.Result> backup, JobRuns jobRuns, Notifier notifier, Heartbeat heartbeat,
                     boolean enabled) {
        this.backup = backup;
        this.jobRuns = jobRuns;
        this.notifier = notifier;
        this.heartbeat = heartbeat;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${robo.backup.cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!enabled) {
            log.info("백업이 꺼져 있음 (robo.backup.enabled=false)");
            return;
        }
        run();
    }

    /** 최근 성공이 너무 오래됐거나 한 번도 없으면 지금 한 번 실행한다. */
    public boolean catchUpIfStale(Clock clock) {
        if (!enabled) {
            return false;
        }
        Optional<Instant> last = jobRuns.lastSuccessAt(JOB);
        if (last.isPresent() && last.get().isAfter(clock.instant().minus(STALE_AFTER))) {
            return false;
        }
        log.warn("최근 {}일 안에 성공한 백업이 없어 지금 실행함", STALE_AFTER.toDays());
        run();
        return true;
    }

    /** 예약 실행과 수동 실행이 겹치지 않게 한 번에 하나만. */
    public synchronized Summary run() {
        long id = jobRuns.start(JOB);
        try {
            BackupService.Result r = backup.get();
            jobRuns.finish(id, JobRuns.Status.SUCCESS, null);
            log.info("백업 완료: {} ({}개 테이블, {}행)", r.file().getFileName(), r.tables(), r.rows());
            signal(true, "");
            return new Summary("OK", r.file().getFileName().toString(), r.rows());
        } catch (RuntimeException e) {
            log.error("백업 실패", e);
            jobRuns.finish(id, JobRuns.Status.FAILED, e.toString());
            try {
                notifier.send(NotificationMessages.failure("백업", e));
            } catch (RuntimeException ex) {
                log.warn("알림 전송 중 예외 ({})", ex.getClass().getSimpleName());
            }
            signal(false, "backup failed");
            return new Summary("FAILED", null, 0);
        }
    }

    private void signal(boolean ok, String reason) {
        try {
            if (ok) {
                heartbeat.success();
            } else {
                heartbeat.failure(reason);
            }
        } catch (RuntimeException e) {
            log.warn("완료 신호 처리 중 예외 ({})", e.getClass().getSimpleName());
        }
    }
}

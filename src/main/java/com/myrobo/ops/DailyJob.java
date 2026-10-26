package com.myrobo.ops;

import com.myrobo.collector.CollectorTypes.CollectResult;
import com.myrobo.ops.JobRuns.Status;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.function.Supplier;

/**
 * 매일 08:00(KST): 수집 → 주문안 만들기 → 알림.
 *
 * <p>주문안은 "계산해서 알려 주는 것"까지만 자동이고, 주문은 사람이 증권사 앱에서 넣는다.
 * 수집에 오류가 있으면 일부 종목만 갱신된 상태로 주문안을 내지 않도록 그날은 만들지 않는다
 * (경고만 있으면 계속). 알림이 실패해도, 한 단계가 예외로 멈춰도 사람에게 알리고 끝낸다.
 */
public class DailyJob {

    public static final String PLAN_JOB = "plan-daily";
    private static final Logger log = LoggerFactory.getLogger(DailyJob.class);

    /** 실행 설정. collectorEnabled가 꺼져 있으면 예약 실행을 하지 않는다. autoPlan이 꺼지면 수집만 한다. */
    public record Settings(boolean collectorEnabled, boolean autoPlan) {
    }

    /** 수동 실행 응답용 요약. */
    public record Summary(String collection, String plan) {
    }

    private final Supplier<CollectResult> collect;
    private final Supplier<CreateResult> createPlan;
    private final JobRuns jobRuns;
    private final Notifier notifier;
    private final Settings settings;

    public DailyJob(Supplier<CollectResult> collect, Supplier<CreateResult> createPlan, JobRuns jobRuns,
                    Notifier notifier, Settings settings) {
        this.collect = collect;
        this.createPlan = createPlan;
        this.jobRuns = jobRuns;
        this.notifier = notifier;
        this.settings = settings;
    }

    @Scheduled(cron = "${robo.collector.cron}", zone = "Asia/Seoul")
    public void scheduled() {
        if (!settings.collectorEnabled()) {
            log.info("수집이 꺼져 있음 (robo.collector.enabled=false)");
            return;
        }
        run();
    }

    /** 예약 실행과 수동 실행이 겹치지 않게 한 번에 하나만. */
    public synchronized Summary run() {
        CollectResult c;
        try {
            c = collect.get();
        } catch (RuntimeException e) {
            log.error("수집 중단으로 주문안을 만들지 않음", e);
            notifier.send(NotificationMessages.failure("수집", e));
            return new Summary("ABORTED", "SKIPPED");
        }
        if (!c.ok()) {
            log.warn("수집 오류 {}건이라 오늘 주문안을 만들지 않음", c.errors().size());
            skipRecord("수집 오류로 건너뜀: " + String.join(" / ", c.errors()));
            notifier.send(NotificationMessages.collectionErrors(c.errors()));
            return new Summary("ERRORS", "SKIPPED");
        }
        if (!settings.autoPlan()) {
            log.info("자동 주문안이 꺼져 있음 (robo.daily.auto-plan=false)");
            return new Summary("OK", "DISABLED");
        }
        return new Summary("OK", plan());
    }

    private String plan() {
        long id = jobRuns.start(PLAN_JOB);
        try {
            CreateResult r = createPlan.get();
            // 폐기는 안전장치가 정상 동작한 결과지만 사람이 봐야 하므로 FAILED로 남겨 눈에 띄게 한다
            boolean rejected = r.outcome() == Outcome.REJECTED;
            jobRuns.finish(id, rejected ? Status.FAILED : Status.SUCCESS, rejected ? r.message() : null);
            log.info("자동 주문안: {} - {}", r.outcome(), r.message());
            NotificationMessages.forPlan(r).ifPresent(notifier::send);
            return r.outcome().name();
        } catch (RuntimeException e) {
            log.error("주문안 자동 생성 중단", e);
            jobRuns.finish(id, Status.FAILED, e.toString());
            notifier.send(NotificationMessages.failure("주문안 만들기", e));
            return "ERROR";
        }
    }

    private void skipRecord(String reason) {
        long id = jobRuns.start(PLAN_JOB);
        jobRuns.finish(id, Status.SKIPPED, reason);
    }
}

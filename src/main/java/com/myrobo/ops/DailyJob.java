package com.myrobo.ops;

import com.myrobo.collector.CollectorTypes.CollectResult;
import com.myrobo.ops.JobRuns.Status;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 매일 08:00(KST): 수집 → 주문안 만들기 → 알림.
 *
 * <p>주문안은 "계산해서 알려 주는 것"까지만 자동이고, 주문은 사람이 증권사 앱에서 넣는다.
 * 수집에 오류가 있으면 일부 종목만 갱신된 상태로 주문안을 내지 않도록 그날은 만들지 않는다
 * (경고만 있으면 계속). 알림이 실패해도, 한 단계가 예외로 멈춰도 사람에게 알리고 끝낸다.
 *
 * <p>끝나면 외부 감시 서비스에 완료 신호를 보낸다 (사람이 볼 일이 있었거나 알림 전송이 실패했으면 실패 신호).
 * 알림 전송이 실패하면 기록해 두었다가 다음 알림 앞에 그 사실을 붙인다.
 */
public class DailyJob {

    public static final String PLAN_JOB = "plan-daily";
    /** 알림 전송 실패 기록용. FAILED가 마지막이면 다음 알림 앞에 안내를 붙인다. */
    public static final String NOTIFY_JOB = "notify-daily";
    static final String MISSED_NOTICE = "[myrobo] 지난번 알림이 전송에 실패했어요. 놓친 알림이 있는지 앱 기록을 확인하세요.";
    private static final Set<String> HEALTHY_PLANS = Set.of("SENT", "ALREADY_TODAY", "NO_ACTION", "DISABLED");
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
    private final Heartbeat heartbeat;

    /** 한 번의 실행 동안만 쓰는 상태 (run()이 synchronized라 겹치지 않는다). */
    private String pendingNotice;
    private boolean noticeDelivered;
    private boolean notifyFailed;

    public DailyJob(Supplier<CollectResult> collect, Supplier<CreateResult> createPlan, JobRuns jobRuns,
                    Notifier notifier, Settings settings, Heartbeat heartbeat) {
        this.collect = collect;
        this.createPlan = createPlan;
        this.jobRuns = jobRuns;
        this.notifier = notifier;
        this.settings = settings;
        this.heartbeat = heartbeat;
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
        pendingNotice = previousNotifyFailed() ? MISSED_NOTICE : null;
        noticeDelivered = false;
        notifyFailed = false;
        Summary summary = steps();
        recordNotifyOutcome();
        signal(summary);
        return summary;
    }

    private Summary steps() {
        CollectResult c;
        try {
            c = collect.get();
        } catch (RuntimeException e) {
            log.error("수집 중단으로 주문안을 만들지 않음", e);
            notify(NotificationMessages.failure("수집", e));
            return new Summary("ABORTED", "SKIPPED");
        }
        if (!c.ok()) {
            log.warn("수집 오류 {}건이라 오늘 주문안을 만들지 않음", c.errors().size());
            skipRecord("수집 오류로 건너뜀: " + String.join(" / ", c.errors()));
            notify(NotificationMessages.collectionErrors(c.errors()));
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
            NotificationMessages.forPlan(r).ifPresent(this::notify);
            return r.outcome().name();
        } catch (RuntimeException e) {
            log.error("주문안 자동 생성 중단", e);
            jobRuns.finish(id, Status.FAILED, e.toString());
            notify(NotificationMessages.failure("주문안 만들기", e));
            return "ERROR";
        }
    }

    private void skipRecord(String reason) {
        long id = jobRuns.start(PLAN_JOB);
        jobRuns.finish(id, Status.SKIPPED, reason);
    }

    /** 알림을 보낸다. 직전 전송 실패가 있었으면 첫 메시지 앞에 안내를 붙인다. 꺼져 있는 건 실패로 치지 않는다. */
    private void notify(String text) {
        String body = pendingNotice == null ? text : pendingNotice + "\n\n" + text;
        boolean sent;
        try {
            sent = notifier.send(body);
        } catch (RuntimeException e) {
            log.warn("알림 전송 중 예외 ({})", e.getClass().getSimpleName());
            sent = false;
        }
        if (sent) {
            if (pendingNotice != null) {
                noticeDelivered = true;
                pendingNotice = null;
            }
        } else if (notifier.enabled()) {
            notifyFailed = true;
        }
    }

    private boolean previousNotifyFailed() {
        try {
            return jobRuns.lastStatus(NOTIFY_JOB).filter(st -> st == Status.FAILED).isPresent();
        } catch (RuntimeException e) {
            log.warn("알림 기록을 읽지 못함 ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 전송 실패는 FAILED로 남기고, 지난 실패 안내를 이번에 전했으면 SUCCESS로 덮어 안내를 멈춘다. */
    private void recordNotifyOutcome() {
        if (!notifyFailed && !noticeDelivered) {
            return;
        }
        try {
            long id = jobRuns.start(NOTIFY_JOB);
            jobRuns.finish(id, notifyFailed ? Status.FAILED : Status.SUCCESS, notifyFailed ? "알림 전송 실패" : null);
        } catch (RuntimeException e) {
            log.warn("알림 결과를 기록하지 못함 ({})", e.getClass().getSimpleName());
        }
    }

    /** 사람이 볼 일이 없었고 알림도 잘 갔을 때만 성공 신호. */
    private void signal(Summary s) {
        boolean healthy = "OK".equals(s.collection()) && HEALTHY_PLANS.contains(s.plan()) && !notifyFailed;
        try {
            if (healthy) {
                heartbeat.success();
            } else {
                heartbeat.failure("collection=" + s.collection() + " plan=" + s.plan()
                        + (notifyFailed ? " notify=FAILED" : ""));
            }
        } catch (RuntimeException e) {
            log.warn("완료 신호 처리 중 예외 ({})", e.getClass().getSimpleName());
        }
    }
}

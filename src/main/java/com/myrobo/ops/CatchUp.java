package com.myrobo.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * 오늘 몫의 매일 작업이 아직 없으면 한 번 따라잡는다 (PC가 꺼져 있거나 절전이라 08:00을 놓친 경우).
 * "있다"는 오늘(KST) 끝난 plan-daily 기록이 있다는 뜻이며 상태는 가리지 않는다. 같은 날 두 번 돌지 않게 하려는 것이다.
 *
 * <p>{@link Ticker}가 주기적으로 부른다. 수집이 예외로 멈춘 날은 기록이 남지 않으므로, 같은 날 같은 프로세스에서는
 * 한 번만 시도한다 (실패 알림이 10분마다 반복되지 않게). 앱을 다시 켜면 다시 시도한다.
 */
public final class CatchUp {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(CatchUp.class);

    private final Supplier<DailyJob.Summary> run;
    private final JobRuns jobRuns;
    private final Clock clock;
    private final LocalTime after;
    private final boolean enabled;
    private LocalDate attempted;

    /**
     * @param after 이 시각(KST)이 지나야 따라잡는다. 정규 실행 시각보다 약간 뒤로 잡아 겹치지 않게 한다
     */
    public CatchUp(Supplier<DailyJob.Summary> run, JobRuns jobRuns, Clock clock, LocalTime after, boolean enabled) {
        this.run = run;
        this.jobRuns = jobRuns;
        this.clock = clock;
        this.after = after;
        this.enabled = enabled;
    }

    /** @return 실제로 실행했으면 true */
    public synchronized boolean runIfMissed() {
        if (!enabled) {
            return false;
        }
        var now = clock.instant().atZone(KST);
        if (now.toLocalTime().isBefore(after)) {
            return false;
        }
        LocalDate today = now.toLocalDate();
        if (today.equals(attempted) || jobRuns.finishedOn(DailyJob.PLAN_JOB, today)) {
            return false;
        }
        attempted = today;
        log.warn("오늘({}) 매일 작업 기록이 없어 지금 따라잡음", today);
        run.get();
        return true;
    }
}

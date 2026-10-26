package com.myrobo.collector;

import com.myrobo.collector.CollectorTypes.CollectResult;
import com.myrobo.common.config.LoadedConfig;
import com.myrobo.ops.JobRuns;
import com.myrobo.ops.JobRuns.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.TreeSet;

/**
 * 수집 한 번 실행. 08:00(KST)은 겨울(EST)에도 미국 장 마감 + 일봉 반영(17:45 ET) 이후가 되도록 잡은 시각이다.
 * 실행 결과는 job_run에 남는다.
 */
@Component
public class CollectorJob {

    public static final String JOB = "collect-daily";
    private static final Logger log = LoggerFactory.getLogger(CollectorJob.class);

    private final CollectorService service;
    private final LoadedConfig config;
    private final JobRuns jobRuns;

    public CollectorJob(CollectorService service, LoadedConfig config, JobRuns jobRuns) {
        this.service = service;
        this.config = config;
        this.jobRuns = jobRuns;
    }

    /** 예약은 ops.DailyJob이 한다 (수집 → 주문안 → 알림). 여기는 수집 한 번만. 한 번에 하나만 돈다. */
    public synchronized CollectResult run() {
        long id = jobRuns.start(JOB);
        try {
            // 1단계: 코어 종목. 보유 종목·관심 종목은 입력 화면과 위성 모듈이 생기면 추가
            var symbols = new TreeSet<>(config.config().whitelist());
            CollectResult r = service.runDaily(Instant.now(), symbols);

            r.prices().forEach(p -> log.info("수집 {} {} rows={} latest={}{}", p.symbol(), p.mode(), p.rows(),
                    p.latest(), p.message() == null ? "" : " (" + p.message() + ")"));
            if (r.fx() != null) {
                log.info("환율 {} {} ({})", r.fx().date(), r.fx().rate(), r.fx().source());
            }
            r.warnings().forEach(w -> log.warn("수집 경고: {}", w));
            r.errors().forEach(e -> log.error("수집 오류: {}", e));

            jobRuns.finish(id, r.ok() ? Status.SUCCESS : Status.FAILED,
                    r.ok() ? null : String.join("\n", r.errors()));
            return r;
        } catch (RuntimeException e) {
            log.error("수집 중단", e);
            jobRuns.finish(id, Status.FAILED, e.toString());
            throw e;
        }
    }
}

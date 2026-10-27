package com.myrobo.ops;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** 배치 실행 기록 (job_run). 시작·종료 시각과 결과를 남겨 "조용히 실패"를 막는다. */
@Component
public class JobRuns {

    static final String START = "insert into job_run (job, status) values (:job, 'RUNNING') returning id";
    static final String FINISH = "update job_run set finished_at = now(), status = :status, error = :error where id = :id";

    static final String LAST_STATUS = "select status from job_run where job = :job and status <> 'RUNNING' order by id desc limit 1";
    static final String FINISHED_ON = "select exists (select 1 from job_run where job = :job and status <> 'RUNNING'"
            + " and cast(started_at at time zone 'Asia/Seoul' as date) = :day)";

    static final String LAST_SUCCESS_AT = "select cast(extract(epoch from finished_at) as bigint) from job_run"
            + " where job = :job and status = 'SUCCESS' and finished_at is not null order by id desc limit 1";

    public enum Status { SUCCESS, FAILED, SKIPPED }

    private final JdbcClient jdbc;

    public JobRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String job) {
        return jdbc.sql(START).param("job", job).query(Long.class).single();
    }

    public void finish(long id, Status status, String error) {
        jdbc.sql(FINISH)
                .param("status", status.name())
                .param("error", error)
                .param("id", id)
                .update();
    }

    /** 끝난 가장 최근 기록의 상태. 기록이 없으면 비어 있다. */
    public Optional<Status> lastStatus(String job) {
        return jdbc.sql(LAST_STATUS).param("job", job).query(String.class).optional().map(Status::valueOf);
    }

    /** 가장 최근에 성공으로 끝난 시각. 기록이 없으면 비어 있다. */
    public Optional<Instant> lastSuccessAt(String job) {
        return jdbc.sql(LAST_SUCCESS_AT).param("job", job).query(Long.class).optional().map(Instant::ofEpochSecond);
    }

    /** 그 날짜(KST)에 시작해 끝난 기록이 있는지. 중간에 죽어 RUNNING으로 남은 건 세지 않는다. */
    public boolean finishedOn(String job, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.sql(FINISHED_ON).param("job", job).param("day", day)
                .query(Boolean.class).single());
    }
}

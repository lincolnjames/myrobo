package com.myrobo.ops;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 배치 실행 기록 (job_run). 시작·종료 시각과 결과를 남겨 "조용히 실패"를 막는다. */
@Component
public class JobRuns {

    static final String START = "insert into job_run (job, status) values (:job, 'RUNNING') returning id";
    static final String FINISH = "update job_run set finished_at = now(), status = :status, error = :error where id = :id";

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
}

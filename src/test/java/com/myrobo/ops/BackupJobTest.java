package com.myrobo.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 주간 백업 작업: 기록, 알림, 완료 신호, 놓친 백업 따라잡기. */
class BackupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        final List<String> pings = new ArrayList<>();
        RuntimeException failure;
        Optional<Instant> lastSuccess = Optional.empty();
        boolean enabled = true;

        final JobRuns jobRuns = new JobRuns(null) {
            @Override public long start(String job) { calls.add("start " + job); return 1; }
            @Override public void finish(long id, Status status, String error) { calls.add("finish " + status); }
            @Override public Optional<Instant> lastSuccessAt(String job) { return lastSuccess; }
        };
        final Notifier notifier = new Notifier() {
            @Override public boolean send(String text) { sent.add(text); return true; }
            @Override public boolean enabled() { return true; }
        };
        final Heartbeat heartbeat = new Heartbeat() {
            @Override public void success() { pings.add("ok"); }
            @Override public void failure(String reason) { pings.add("fail " + reason); }
        };

        BackupJob job() {
            return new BackupJob(() -> {
                calls.add("backup");
                if (failure != null) { throw failure; }
                return new BackupService.Result(Path.of("backups", "myrobo-2026-11-08.zip"), 1234, 16);
            }, jobRuns, notifier, heartbeat, enabled);
        }
    }

    @Test
    void aSuccessIsRecordedAndSignalledButNotAnnounced() {
        var f = new Fixture();

        var s = f.job().run();

        assertEquals(new BackupJob.Summary("OK", "myrobo-2026-11-08.zip", 1234), s);
        assertEquals(List.of("start backup-weekly", "backup", "finish SUCCESS"), f.calls);
        assertEquals(List.of("ok"), f.pings);
        assertTrue(f.sent.isEmpty());
    }

    @Test
    void aFailureIsRecordedAnnouncedAndSignalled() {
        var f = new Fixture();
        f.failure = new IllegalStateException("goal: 내보낸 행 2개가 DB의 1개와 다름");

        var s = f.job().run();

        assertEquals("FAILED", s.status());
        assertEquals(List.of("start backup-weekly", "backup", "finish FAILED"), f.calls);
        assertEquals(List.of("fail backup failed"), f.pings);
        assertEquals(1, f.sent.size());
        assertTrue(f.sent.get(0).contains("백업"), f.sent.get(0));
        assertTrue(f.sent.get(0).contains("내보낸 행"), f.sent.get(0));
    }

    @Test
    void aDisabledJobDoesNothingOnSchedule() {
        var f = new Fixture();
        f.enabled = false;

        f.job().scheduled();

        assertTrue(f.calls.isEmpty());
        assertTrue(f.pings.isEmpty());
    }

    @Test
    void catchUpRunsWhenThereIsNeverASuccess() {
        var f = new Fixture();

        assertTrue(f.job().catchUpIfStale(CLOCK));

        assertEquals(List.of("start backup-weekly", "backup", "finish SUCCESS"), f.calls);
    }

    @Test
    void catchUpRunsWhenTheLastSuccessIsOlderThanEightDays() {
        var f = new Fixture();
        f.lastSuccess = Optional.of(NOW.minus(Duration.ofDays(9)));

        assertTrue(f.job().catchUpIfStale(CLOCK));
    }

    @Test
    void catchUpSkipsWhenTheLastSuccessIsRecent() {
        var f = new Fixture();
        f.lastSuccess = Optional.of(NOW.minus(Duration.ofDays(7)));

        assertFalse(f.job().catchUpIfStale(CLOCK)); // 정상적인 주 1회 주기에서는 따라잡기가 끼어들지 않는다

        assertTrue(f.calls.isEmpty());
    }

    @Test
    void catchUpDoesNothingWhenDisabled() {
        var f = new Fixture();
        f.enabled = false;

        assertFalse(f.job().catchUpIfStale(CLOCK));

        assertTrue(f.calls.isEmpty());
    }
}

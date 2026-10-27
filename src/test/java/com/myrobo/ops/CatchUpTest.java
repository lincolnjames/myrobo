package com.myrobo.ops;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 놓친 날 따라잡기: 언제 돌고 언제 안 도는지. */
class CatchUpTest {

    private static final LocalTime AFTER = LocalTime.of(8, 10);

    private static final class Fixture {
        final List<String> runs = new ArrayList<>();
        final List<LocalDate> asked = new ArrayList<>();
        boolean alreadyDone;

        final JobRuns jobRuns = new JobRuns(null) {
            @Override public boolean finishedOn(String job, LocalDate day) {
                assertEquals(DailyJob.PLAN_JOB, job);
                asked.add(day);
                return alreadyDone;
            }
        };

        CatchUp at(String utcInstant, boolean enabled) {
            return new CatchUp(() -> { runs.add("run"); return new DailyJob.Summary("OK", "SENT"); }, jobRuns,
                    Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC), AFTER, enabled);
        }
    }

    @Test
    void runsWhenPastTheTimeAndNothingRanToday() {
        var f = new Fixture();

        assertTrue(f.at("2026-11-02T01:00:00Z", true).runIfMissed()); // KST 10:00

        assertEquals(List.of("run"), f.runs);
        assertEquals(List.of(LocalDate.of(2026, 11, 2)), f.asked);
    }

    @Test
    void usesTheKoreanDateNotTheUtcDate() {
        var f = new Fixture();

        f.at("2026-11-01T23:30:00Z", true).runIfMissed(); // KST 11-02 08:30, UTC로는 아직 11-01

        assertEquals(List.of(LocalDate.of(2026, 11, 2)), f.asked);
    }

    @Test
    void doesNothingBeforeTheTime() {
        var f = new Fixture();

        assertFalse(f.at("2026-11-01T23:05:00Z", true).runIfMissed()); // KST 08:05

        assertTrue(f.runs.isEmpty());
        assertTrue(f.asked.isEmpty());
    }

    @Test
    void doesNothingWhenTodayAlreadyRan() {
        var f = new Fixture();
        f.alreadyDone = true;

        assertFalse(f.at("2026-11-02T01:00:00Z", true).runIfMissed());

        assertTrue(f.runs.isEmpty());
    }

    @Test
    void doesNothingWhenDisabled() {
        var f = new Fixture();

        assertFalse(f.at("2026-11-02T01:00:00Z", false).runIfMissed());

        assertTrue(f.runs.isEmpty());
        assertTrue(f.asked.isEmpty());
    }
}

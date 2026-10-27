package com.myrobo.ops;

import com.myrobo.collector.CollectorTypes.CollectResult;
import com.myrobo.ops.JobRuns.Status;
import com.myrobo.plan.PlanViews.CheckView;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.Outcome;
import com.myrobo.plan.PlanViews.PlanView;
import com.myrobo.valuation.Drawdown;
import com.myrobo.valuation.ValuationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 매일 작업의 순서와 분기, 알림 실패·단계 실패 처리. */
class DailyJobTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    static PlanView plan(String status) {
        return new PlanView(7, MON, "INITIAL_BUILD", status, "REBALANCE", "INITIAL_BUILD",
                "필요한 매매가 총자산의 163.05%라 25%씩 매주 나눠 진행", new BigDecimal("5000.00"), new BigDecimal("25.00"),
                3000L, 3_990_000L, MON.plusDays(2), OffsetDateTime.of(2026, 11, 1, 23, 0, 0, 0, ZoneOffset.UTC),
                List.of(new ItemView(1, "BUY", "VTI", 5, new BigDecimal("373.01"), new BigDecimal("1865.05")),
                        new ItemView(2, "SELL", "BIL", 10, new BigDecimal("91.63"), new BigDecimal("916.30"))),
                List.of());
    }

    static CreateResult result(Outcome o, PlanView p, List<CheckView> checks) {
        return new CreateResult(o, o + " 메시지", p, checks);
    }

    private static CollectResult collected(List<String> errors) {
        return new CollectResult(MON.minusDays(1), List.of(), null, 0, List.of("경고 하나"), errors);
    }

    /** 호출을 기록하는 가짜 작업 기록·알림·단계. */
    private static final class Fakes {
        final List<String> calls = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        CollectResult collect = collected(List.of());
        CreateResult plan = result(Outcome.SENT, plan("SENT"), List.of());
        RuntimeException collectFails;
        RuntimeException planFails;
        boolean notifierThrows;
        boolean notifierEnabled = true;
        boolean notifierFails;
        Optional<Status> lastNotifyStatus = Optional.empty();
        final List<String> pings = new ArrayList<>();
        boolean autoPlan = true;
        long nextId = 1;
        ValuationService.Outcome valuation = new ValuationService.Outcome(ValuationService.Status.RECORDED, "낙폭 0%",
                null, Optional.empty());
        RuntimeException valuationFails;
        int valuations;

        final JobRuns jobRuns = new JobRuns(null) {
            @Override public long start(String job) { calls.add("start " + job); return nextId++; }
            @Override public void finish(long id, Status status, String error) { calls.add("finish " + status); }
            @Override public Optional<Status> lastStatus(String job) { return lastNotifyStatus; }
        };
        final Notifier notifier = new Notifier() {
            @Override public boolean send(String text) {
                if (notifierThrows) { throw new IllegalStateException("알림 장애"); }
                if (notifierFails) { return false; }
                sent.add(text); return true;
            }
            @Override public boolean enabled() { return notifierEnabled; }
        };
        final Heartbeat heartbeat = new Heartbeat() {
            @Override public void success() { pings.add("ok"); }
            @Override public void failure(String reason) { pings.add("fail " + reason); }
        };

        DailyJob job() {
            return new DailyJob(
                    () -> { calls.add("collect"); if (collectFails != null) { throw collectFails; } return collect; },
                    () -> { valuations++; if (valuationFails != null) { throw valuationFails; } return valuation; },
                    () -> { calls.add("plan"); if (planFails != null) { throw planFails; } return plan; },
                    jobRuns, notifier, new DailyJob.Settings(true, autoPlan), heartbeat);
        }
    }

    @Test
    void collectsThenPlansThenNotifiesInThatOrder() {
        var f = new Fakes();

        var s = f.job().run();

        assertEquals(new DailyJob.Summary("OK", "RECORDED", "SENT"), s);
        assertEquals(List.of("collect", "start plan-daily", "plan", "finish SUCCESS"), f.calls);
        assertEquals(1, f.sent.size());
        assertTrue(f.sent.get(0).contains("주문안 #7 발송"), f.sent.get(0));
    }

    @Test
    void collectionErrorsSkipThePlanAndTellTheUser() {
        var f = new Fakes();
        f.collect = collected(List.of("VTI 일봉 수집 실패: 시간 초과"));

        var s = f.job().run();

        assertEquals(new DailyJob.Summary("ERRORS", "SKIPPED", "SKIPPED"), s);
        assertFalse(f.calls.contains("plan"), f.calls.toString()); // 주문안을 만들지 않는다
        assertEquals(List.of("collect", "start plan-daily", "finish SKIPPED"), f.calls);
        assertEquals(1, f.sent.size());
        assertTrue(f.sent.get(0).contains("VTI 일봉 수집 실패"), f.sent.get(0));
    }

    @Test
    void warningsAloneDoNotStopThePlan() {
        var f = new Fakes(); // collected()에는 경고가 하나 들어 있다

        assertEquals("SENT", f.job().run().plan());
    }

    @Test
    void anAbortedCollectionSkipsThePlanAndTellsTheUser() {
        var f = new Fakes();
        f.collectFails = new IllegalStateException("DB 연결 끊김");

        var s = f.job().run();

        assertEquals(new DailyJob.Summary("ABORTED", "SKIPPED", "SKIPPED"), s);
        assertEquals(List.of("collect"), f.calls);
        assertTrue(f.sent.get(0).contains("수집"), f.sent.get(0));
        assertTrue(f.sent.get(0).contains("DB 연결 끊김"), f.sent.get(0));
    }

    @Test
    void autoPlanCanBeTurnedOffAndCollectionStillRuns() {
        var f = new Fakes();
        f.autoPlan = false;

        var s = f.job().run();

        assertEquals(new DailyJob.Summary("OK", "RECORDED", "DISABLED"), s);
        assertEquals(List.of("collect"), f.calls);
        assertTrue(f.sent.isEmpty());
    }

    @Test
    void aRejectedPlanIsRecordedAsFailedAndWarned() {
        var f = new Fakes();
        f.plan = result(Outcome.REJECTED, plan("REJECTED"),
                List.of(new CheckView("PRICE_FRESH", "시세 신선도", false, "3거래일 전 시세")));

        var s = f.job().run();

        assertEquals("REJECTED", s.plan());
        assertEquals(List.of("collect", "start plan-daily", "plan", "finish FAILED"), f.calls);
        assertTrue(f.sent.get(0).contains("주문하지 마세요"), f.sent.get(0));
    }

    @Test
    void aCrashingPlanStepIsRecordedAndReported() {
        var f = new Fakes();
        f.planFails = new IllegalStateException("코어 계좌가 등록되지 않음");

        var s = f.job().run();

        assertEquals("ERROR", s.plan());
        assertEquals(List.of("collect", "start plan-daily", "plan", "finish FAILED"), f.calls);
        assertTrue(f.sent.get(0).contains("코어 계좌가 등록되지 않음"), f.sent.get(0));
    }

    @Test
    void quietOutcomesSendNothing() {
        for (Outcome o : List.of(Outcome.ALREADY_TODAY, Outcome.NO_ACTION)) {
            var f = new Fakes();
            f.plan = result(o, o == Outcome.ALREADY_TODAY ? plan("SENT") : null, List.of());

            assertEquals(o.name(), f.job().run().plan());
            assertTrue(f.sent.isEmpty(), o.toString());
        }
    }

    @Test
    void scheduledRunDoesNothingWhenCollectionIsDisabled() {
        var f = new Fakes();
        var off = new DailyJob(() -> { f.calls.add("collect"); return f.collect; }, () -> f.valuation, () -> f.plan,
                f.jobRuns, f.notifier, new DailyJob.Settings(false, true), f.heartbeat);

        off.scheduled();

        assertTrue(f.calls.isEmpty());
        assertTrue(f.pings.isEmpty());
    }

    @Test
    void valuationRunsAfterCollectionEvenWhenAutoPlanIsOff() {
        var on = new Fakes();
        var off = new Fakes();
        off.autoPlan = false;

        on.job().run();
        off.job().run();

        assertEquals(1, on.valuations);
        assertEquals(1, off.valuations);
    }

    @Test
    void collectionErrorsSkipTheValuationToo() {
        var f = new Fakes();
        f.collect = collected(List.of("VTI 일봉 수집 실패"));

        assertEquals("SKIPPED", f.job().run().valuation());

        assertEquals(0, f.valuations);
    }

    @Test
    void aDrawdownAlertIsSentAndThePlanStillRuns() {
        var f = new Fakes();
        var level = new Drawdown.Level(1, new BigDecimal("15"), "ALERT");
        f.valuation = new ValuationService.Outcome(ValuationService.Status.RECORDED, "낙폭 16.2%", null,
                Optional.of(new Drawdown.Alert(Drawdown.Alert.Kind.UP, level, new BigDecimal("16.2"),
                        LocalDate.of(2026, 11, 3))));

        var s = f.job().run();

        assertEquals("SENT", s.plan());
        assertEquals(2, f.sent.size());
        assertTrue(f.sent.get(0).contains("낙폭 경보"), f.sent.get(0));
        assertTrue(f.sent.get(0).contains("16.20%"), f.sent.get(0));
        assertEquals(List.of("ok"), f.pings); // 경보는 정상 동작이라 완료 신호는 성공
    }

    @Test
    void aFailedValuationDoesNotStopThePlanButFailsTheSignal() {
        var f = new Fakes();
        f.valuation = new ValuationService.Outcome(ValuationService.Status.FAILED, "평가에 필요한 값이 없음: VTI", null,
                Optional.empty());

        var s = f.job().run();

        assertEquals(new DailyJob.Summary("OK", "FAILED", "SENT"), s);
        assertEquals(List.of("fail collection=OK valuation=FAILED plan=SENT"), f.pings);
    }

    @Test
    void aCrashingValuationIsTreatedAsFailed() {
        var f = new Fakes();
        f.valuationFails = new IllegalStateException("DB 끊김");

        var s = f.job().run();

        assertEquals("FAILED", s.valuation());
        assertEquals("SENT", s.plan());
    }

    @Test
    void aSkippedValuationIsNotAFailure() {
        var f = new Fakes();
        f.valuation = new ValuationService.Outcome(ValuationService.Status.SKIPPED, "잔고 입력 전", null,
                Optional.empty());

        f.job().run();

        assertEquals(List.of("ok"), f.pings);
    }

    @Test
    void aCleanRunSendsTheSuccessSignal() {
        var f = new Fakes();

        f.job().run();

        assertEquals(List.of("ok"), f.pings);
    }

    @Test
    void quietAndDisabledPlansStillCountAsHealthy() {
        var f = new Fakes();
        f.plan = result(Outcome.NO_ACTION, null, List.of());
        f.job().run();
        var g = new Fakes();
        g.autoPlan = false;
        g.job().run();

        assertEquals(List.of("ok"), f.pings);
        assertEquals(List.of("ok"), g.pings);
    }

    @Test
    void problemsSendTheFailureSignalWithoutDetails() {
        var errors = new Fakes();
        errors.collect = collected(List.of("VTI 일봉 수집 실패: 시간 초과"));
        var aborted = new Fakes();
        aborted.collectFails = new IllegalStateException("DB 연결 끊김");
        var rejected = new Fakes();
        rejected.plan = result(Outcome.REJECTED, plan("REJECTED"), List.of());
        var crashed = new Fakes();
        crashed.planFails = new IllegalStateException("코어 계좌가 등록되지 않음");

        errors.job().run();
        aborted.job().run();
        rejected.job().run();
        crashed.job().run();

        assertEquals(List.of("fail collection=ERRORS valuation=SKIPPED plan=SKIPPED"), errors.pings);
        assertEquals(List.of("fail collection=ABORTED valuation=SKIPPED plan=SKIPPED"), aborted.pings);
        assertEquals(List.of("fail collection=OK valuation=RECORDED plan=REJECTED"), rejected.pings);
        assertEquals(List.of("fail collection=OK valuation=RECORDED plan=ERROR"), crashed.pings);
    }

    @Test
    void aFailedNotificationIsRecordedAndFlaggedInTheSignal() {
        var f = new Fakes();
        f.notifierFails = true;

        f.job().run();

        assertEquals(List.of("collect", "start plan-daily", "plan", "finish SUCCESS",
                "start notify-daily", "finish FAILED"), f.calls);
        assertEquals(List.of("fail collection=OK valuation=RECORDED plan=SENT notify=FAILED"), f.pings);
    }

    @Test
    void aThrowingNotifierDoesNotStopTheRunAndCountsAsFailed() {
        var f = new Fakes();
        f.notifierThrows = true;

        var s = f.job().run();

        assertEquals("SENT", s.plan());
        assertTrue(f.pings.get(0).contains("notify=FAILED"), f.pings.toString());
    }

    @Test
    void aDisabledNotifierIsNotAFailure() {
        var f = new Fakes();
        f.notifierEnabled = false;
        f.notifierFails = true; // 꺼진 알림은 false를 돌려준다

        f.job().run();

        assertEquals(List.of("collect", "start plan-daily", "plan", "finish SUCCESS"), f.calls);
        assertEquals(List.of("ok"), f.pings);
    }

    @Test
    void afterAFailedNotificationTheNextMessageCarriesANoticeOnceAndClearsIt() {
        var f = new Fakes();
        f.lastNotifyStatus = Optional.of(Status.FAILED);

        f.job().run();

        assertEquals(1, f.sent.size());
        assertTrue(f.sent.get(0).startsWith(DailyJob.MISSED_NOTICE), f.sent.get(0));
        assertTrue(f.sent.get(0).contains("주문안 #7 발송"), f.sent.get(0));
        assertEquals(List.of("collect", "start plan-daily", "plan", "finish SUCCESS",
                "start notify-daily", "finish SUCCESS"), f.calls);
    }

    @Test
    void noNoticeWhenThereIsNothingToSayAndNoRecordIsWritten() {
        var f = new Fakes();
        f.lastNotifyStatus = Optional.of(Status.FAILED);
        f.plan = result(Outcome.NO_ACTION, null, List.of());

        f.job().run();

        assertTrue(f.sent.isEmpty());
        assertFalse(f.calls.contains("start notify-daily"), f.calls.toString()); // 안내는 다음 알림까지 남는다
    }

    @Test
    void theNoticeIsNotClearedWhenSendingFailsAgain() {
        var f = new Fakes();
        f.lastNotifyStatus = Optional.of(Status.FAILED);
        f.notifierFails = true;

        f.job().run();

        assertEquals(List.of("collect", "start plan-daily", "plan", "finish SUCCESS",
                "start notify-daily", "finish FAILED"), f.calls);
    }
}

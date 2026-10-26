package com.myrobo.plan;

import com.myrobo.collector.MarketCalendar;
import com.myrobo.common.config.LoadedConfig;
import com.myrobo.common.config.RoboConfig;
import com.myrobo.guard.GuardInput;
import com.myrobo.guard.SafetyGuard;
import com.myrobo.guard.SafetyGuard.Report;
import com.myrobo.holdings.HoldingsService;
import com.myrobo.holdings.HoldingsService.State;
import com.myrobo.plan.PlanDecider.Decision;
import com.myrobo.plan.PlanViews.CheckView;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.Outcome;
import com.myrobo.plan.PlanViews.Overview;
import com.myrobo.plan.PlanViews.PlanView;
import com.myrobo.portfolio.Plan;
import com.myrobo.portfolio.PlanInput;
import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.PlanTypes.Mode;
import com.myrobo.portfolio.RebalancePlanner;
import com.myrobo.portfolio.Valuation.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 주문안 만들기: 일정 판단 → 엔진 계산 → 안전 검증 → 저장(DRAFT) → 발송(SENT) 또는 폐기(REJECTED).
 *
 * <p>계좌별 잠금 안에서 돌기 때문에 버튼을 두 번 누르거나 배치와 겹쳐도 주문안이 두 건 생기지 않는다.
 * DB에도 "미완료 주문안은 계좌당 하나", "같은 날 같은 규칙 버전은 한 건" 제약이 걸려 있다.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int RECENT = 10;

    private final HoldingsService holdings;
    private final PlanRepository repo;
    private final LoadedConfig loaded;
    private final Clock clock;

    public PlanService(HoldingsService holdings, PlanRepository repo, LoadedConfig loaded, Clock clock) {
        this.holdings = holdings;
        this.repo = repo;
        this.loaded = loaded;
        this.clock = clock;
    }

    /** 화면용: 오늘 일정, 미완료 주문안, 최근 주문안. 기한이 지난 주문안은 이때 만료 처리된다. */
    @Transactional
    public Overview overview() {
        long account = holdings.state().account();
        repo.lock(account);
        TradingSchedule sched = schedule();
        repo.expireOverdue(account, sched.tradeDate());
        PlanView open = repo.findOpen(account).flatMap(repo::load).orElse(null);
        return new Overview(sched, open, repo.recent(account, RECENT));
    }

    @Transactional
    public CreateResult create() {
        RoboConfig c = loaded.config();
        State st = holdings.state();
        long account = st.account();
        repo.lock(account);
        Instant now = clock.instant();
        TradingSchedule sched = schedule();

        int expired = repo.expireOverdue(account, sched.tradeDate());
        if (expired > 0) {
            log.info("유효 기간이 지난 주문안 {}건 만료 처리", expired);
        }
        Optional<Long> open = repo.findOpen(account);
        if (open.isPresent()) {
            PlanView p = repo.load(open.get()).orElseThrow();
            return new CreateResult(Outcome.OPEN_EXISTS, "미완료 주문안 #" + p.id() + "이 있어 새로 만들지 않았어요. "
                    + p.expiresAt() + "(미국 거래일)까지 유효하고, 그 뒤 만료돼요.", p, p.checks());
        }

        Built b = build(c, st, sched);
        Report report = SafetyGuard.check(new GuardInput(
                sched.tradeDate(), LocalDate.ofInstant(now, SEOUL), c.goal().startDate(),
                st.asOf(), st.enteredAt(), repo.lastClosedAt(account).orElse(null), null,
                b.priceRequired(), b.priceLag(), st.fx() == null ? null : st.fx().date(),
                b.decision().input(), b.decision().plan(), c.equitySymbols(), limits(c)));
        List<CheckView> checks = report.checks().stream()
                .map(x -> new CheckView(x.rule().name(), x.label(), x.passed(), x.detail())).toList();
        Plan plan = b.decision().plan();

        if (plan.action() == Action.NO_ACTION) {
            return new CreateResult(Outcome.NO_ACTION, noActionMessage(plan, report, c.goal().startDate()), null,
                    checks);
        }

        String base = sched.tradeDate() + "|CORE|" + c.version();
        if (report.passed() && repo.findByKey(base).isPresent()) {
            PlanView p = repo.load(repo.findByKey(base).get()).orElseThrow();
            return new CreateResult(Outcome.ALREADY_TODAY, sched.tradeDate() + " 주문안은 이미 있어요 (#" + p.id()
                    + ", " + p.status() + "). 하루에 한 건만 만들어요.", p, p.checks());
        }
        String key = report.passed() ? base : base + "|REJECTED|" + now.toEpochMilli();
        Map<String, BigDecimal> prices = b.decision().input() == null ? Map.of() : b.decision().input().prices();
        long id = repo.insertDraft(new PlanRepository.NewPlan(account, sched.tradeDate(),
                b.decision().kind().name(), key, c.version(), plan.action().name(), plan.reason(),
                b.decision().note() != null ? b.decision().note() : plan.detail(),
                plan.totalUsd(), plan.sizePct(),
                plan.fx() == null ? null : plan.fx().usd(), plan.fx() == null ? null : plan.fx().krwEst(),
                sched.expiresAt(), plan.lines(), prices));
        repo.insertChecks(id, report.checks());
        if (report.passed()) {
            repo.transition(id, "DRAFT", "SENT");
            log.info("주문안 #{} 발송: {} {} 줄 {}개", id, plan.action(), plan.reason(), plan.lines().size());
            PlanView p = repo.load(id).orElseThrow();
            return new CreateResult(Outcome.SENT, "주문안 #" + id + "을 만들었어요. " + sched.tradeDate()
                    + "부터 " + sched.expiresAt() + "까지(미국 거래일) 이대로 주문하세요.", p, checks);
        }
        repo.transition(id, "DRAFT", "REJECTED");
        List<String> failed = report.failures().stream().map(SafetyGuard.Check::label).toList();
        log.warn("주문안 #{} 폐기: {}", id, failed);
        return new CreateResult(Outcome.REJECTED, "안전 검증에 걸려 주문안 #" + id + "을 폐기했어요: "
                + String.join(", ", failed) + ". 이 계산대로 주문하지 마세요.", repo.load(id).orElseThrow(), checks);
    }

    // ── 내부 ──

    private TradingSchedule schedule() {
        MarketCalendar cal = new MarketCalendar(repo.fullDayClosures());
        return TradingSchedule.of(cal, LocalDate.ofInstant(clock.instant(), SEOUL),
                loaded.config().safety().expireTradingDays());
    }

    private record Built(Decision decision, Set<String> priceRequired, Map<String, Integer> priceLag) {
    }

    private Built build(RoboConfig c, State st, TradingSchedule sched) {
        MarketCalendar cal = new MarketCalendar(repo.fullDayClosures());
        Map<String, BigDecimal> targets = c.targetsFor(c.goal().horizon());
        Set<String> whitelist = c.whitelist();

        Set<String> required = new TreeSet<>();
        targets.keySet().stream().filter(s -> !RebalancePlanner.SATELLITE.equals(s)).forEach(required::add);
        Map<String, Long> held = new HashMap<>();
        for (Position p : st.positions()) {
            held.put(p.symbol(), p.qty());
            if (whitelist.contains(p.symbol()) && p.qty() > 0) {
                required.add(p.symbol());
            }
        }
        Map<String, Integer> lag = new HashMap<>();
        Map<String, BigDecimal> prices = new HashMap<>();
        st.prices().forEach((s, p) -> {
            prices.put(s, p.close());
            lag.put(s, cal.tradingDaysAfter(p.date(), sched.lastSession()));
        });

        Decision d;
        if (st.asOf() == null) {
            d = new Decision(Plan.error("NO_SNAPSHOT", "잔고가 입력되지 않음"), null, PlanDecider.PlanKind.CORE, null);
        } else if (st.fx() == null) {
            d = new Decision(Plan.error("MISSING_FX", "환율이 없음"), null, PlanDecider.PlanKind.CORE, null);
        } else {
            PlanInput normal = new PlanInput(sched.tradeDate(), sched.rebalanceDay(), Mode.NORMAL, true,
                    targets, whitelist, prices, held, st.cash().usd(), st.cash().krw(), st.fx().rate());
            d = PlanDecider.decide(new RebalancePlanner(c.rebalance().toPlannerConfig()), normal,
                    sched.splitDay(), sched.nextSplitDay(), c.rebalance().maxPlanPct());
        }
        return new Built(d, required, lag);
    }

    private static GuardInput.Limits limits(RoboConfig c) {
        RoboConfig.Safety s = c.safety();
        return new GuardInput.Limits(s.priceTradingDays(), s.holdingsDays(), s.fxMaxAgeBusinessDays(),
                c.rebalance().maxPlanPct(), c.equityCapPct(), c.rebalance().minOrderUsd());
    }

    /** 주문이 없다는 판단 자체를 믿을 수 있는지는 데이터 규칙에 달렸다. 시작일 전이라는 건 판단과 무관하다. */
    static String noActionMessage(Plan plan, Report report, LocalDate goalStart) {
        String why = noActionText(plan);
        Set<SafetyGuard.Rule> failed = new java.util.HashSet<>();
        report.failures().forEach(f -> failed.add(f.rule()));
        boolean beforeStart = failed.remove(SafetyGuard.Rule.GOAL_STARTED);
        if (!failed.isEmpty()) {
            return why + " 다만 " + String.join(", ", report.failures().stream()
                    .filter(f -> f.rule() != SafetyGuard.Rule.GOAL_STARTED).map(SafetyGuard.Check::label).toList())
                    + " 검증에 걸려 이 판단도 믿을 수 없어요.";
        }
        return beforeStart ? why + " (목표 시작일 " + goalStart + " 전이라 주문안이 나와도 발송되지 않아요.)" : why;
    }

    static String noActionText(Plan p) {
        return switch (p.reason()) {
            case "WITHIN_BAND" -> "모든 종목이 허용 범위 안이라 오늘은 주문이 없어요.";
            case "NO_EXCESS_CASH" -> "정기 점검일이지만 버퍼(1%)를 넘는 현금이 없어 주문이 없어요.";
            case "ROUNDED_TO_NOTHING" -> "계산된 주문이 모두 1주 미만이거나 최소 주문 금액보다 작아 주문이 없어요.";
            case PlanDecider.SPLIT_WAIT -> p.detail() + ".";
            default -> "오늘은 주문이 없어요 (" + p.reason() + ").";
        };
    }
}

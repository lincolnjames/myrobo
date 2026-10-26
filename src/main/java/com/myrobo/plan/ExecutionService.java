package com.myrobo.plan;

import com.myrobo.holdings.HoldingsRepository;
import com.myrobo.holdings.HoldingsService;
import com.myrobo.holdings.HoldingsService.State;
import com.myrobo.plan.PlanViews.ExecutionResult;
import com.myrobo.plan.PlanViews.ExecutionStatus;
import com.myrobo.plan.PlanViews.FxCheck;
import com.myrobo.plan.PlanViews.PlanView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 체결 입력: 발송(SENT)된 주문안의 실제 체결·환전을 기록하고, 주문안을 DONE으로 닫고, 새 잔고를 만든다.
 * 전부 한 트랜잭션이라 중간에 실패하면 아무것도 남지 않는다. 계좌 잠금과 "SENT일 때만 DONE" 전이 덕분에
 * 같은 체결을 두 번 보내도 한 번만 반영된다.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final HoldingsService holdings;
    private final HoldingsRepository holdingsRepo;
    private final PlanRepository repo;
    private final Clock clock;

    public ExecutionService(HoldingsService holdings, HoldingsRepository holdingsRepo, PlanRepository repo,
                            Clock clock) {
        this.holdings = holdings;
        this.holdingsRepo = holdingsRepo;
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public ExecutionResult save(long planId, ExecutionInput input) {
        State st = holdings.state();
        long account = st.account();
        repo.lock(account);

        PlanView plan = repo.findOpen(account).filter(id -> id == planId).flatMap(repo::load).orElse(null);
        if (plan == null || !"SENT".equals(plan.status())) {
            return conflict("주문안 #" + planId + "은 체결을 입력할 수 있는 발송(SENT) 상태가 아니에요. "
                    + "이미 완료·만료됐거나 없는 주문안이에요. 만료된 주문안은 잔고 화면에서 잔고를 직접 입력하세요.");
        }
        if (ExecutionInput.balanceEnteredAfterPlan(st.enteredAt(), plan.createdAt().toInstant())) {
            return conflict("주문안을 만든 뒤에 잔고를 직접 입력하셨어요. 그 잔고에 이미 체결이 반영됐을 수 있어 "
                    + "체결을 또 더하면 이중으로 계산돼요. 잔고 화면에서 증권사 앱 잔고를 직접 입력해 주세요.");
        }

        List<ExecutionInput.Item> items = plan.items().stream()
                .map(i -> new ExecutionInput.Item(i.id(), i.side(), i.symbol(), i.qty())).toList();
        ExecutionInput.Result checked = input.validate(items);
        if (!checked.ok()) {
            return invalid(checked.errors());
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, SEOUL);
        ExecutionInput.Applied applied = ExecutionInput.apply(checked.clean(), st.positions(),
                st.cash().krw(), st.cash().usd(), today);
        if (!applied.ok()) {
            return invalid(applied.errors());
        }

        // 먼저 닫고(updated_at = 트랜잭션 시작 시각), 그 뒤에 잔고를 저장한다 (clock_timestamp로 더 늦은 시각)
        repo.transition(planId, "SENT", "DONE");
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        checked.clean().executed().forEach(e -> repo.insertExecution(e, at));
        ExecutionInput.Fx fx = checked.clean().fx();
        FxCheck fxCheck = ExecutionInput.fxCheck(fx, plan.fxUsd(), plan.fxKrwEst());
        if (fx != null) {
            String memo = "주문안 #" + planId + " 환전" + (fxCheck.expectedRate() == null ? ""
                    : ": 예상 " + fxCheck.expectedRate().toPlainString() + " 대비 실제 "
                    + fxCheck.actualRate().toPlainString() + " (" + sign(fxCheck) + "%)");
            holdingsRepo.insertLedger(account, today, "FX", "USD", fx.usd(), memo);
            holdingsRepo.insertLedger(account, today, "FX", "KRW", fx.krw().negate(), memo);
        }
        holdingsRepo.replaceSnapshotAfterClose(account, applied.snapshot());

        int filled = checked.clean().executed().size();
        int unfilled = checked.clean().unfilled();
        log.info("주문안 #{} 체결 입력: 체결 {}줄, 미체결 {}줄{}", planId, filled, unfilled,
                fxCheck == null ? "" : ", 환율 " + fxCheck.actualRate());
        return new ExecutionResult(ExecutionStatus.SAVED, savedMessage(planId, filled, unfilled, fxCheck, today),
                List.of(), fxCheck, holdings.view());
    }

    private static String sign(FxCheck f) {
        return f.diffPct().signum() > 0 ? "+" + f.diffPct().toPlainString() : f.diffPct().toPlainString();
    }

    static String savedMessage(long planId, int filled, int unfilled, FxCheck fx, LocalDate today) {
        StringBuilder sb = new StringBuilder("주문안 #").append(planId).append("을 완료 처리하고 ").append(today)
                .append(" 잔고를 갱신했어요. 체결 ").append(filled).append("줄");
        if (unfilled > 0) {
            sb.append(", 미체결 ").append(unfilled).append("줄(잔고에 반영 안 함)");
        }
        sb.append('.');
        if (fx != null && fx.diffPct() != null) {
            sb.append(" 환율은 예상 ").append(fx.expectedRate().toPlainString()).append(" 대비 실제 ")
                    .append(fx.actualRate().toPlainString()).append(" (").append(sign(fx)).append("%).");
        }
        return sb.append(" 증권사 앱의 잔고와 맞는지 꼭 확인하세요.").toString();
    }

    private static ExecutionResult conflict(String message) {
        return new ExecutionResult(ExecutionStatus.CONFLICT, message, List.of(message), null, null);
    }

    private static ExecutionResult invalid(List<String> errors) {
        return new ExecutionResult(ExecutionStatus.INVALID, "저장하지 않았어요. 입력을 확인해 주세요.", errors, null, null);
    }
}

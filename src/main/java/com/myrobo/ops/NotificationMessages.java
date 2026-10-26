package com.myrobo.ops;

import com.myrobo.plan.PlanViews.CheckView;
import com.myrobo.plan.PlanViews.CreateResult;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 알림 문구와 "보낼지 말지" 판단. 순수 계산이라 DB·네트워크 없이 테스트한다.
 * 외부 서비스로 나가는 글이므로 종목·수량·예상가까지만 담고, 총자산 같은 합계는 넣지 않는다.
 */
public final class NotificationMessages {

    private static final String TAG = "[myrobo] ";
    private static final int MAX_LISTED = 10;
    /** 목표 시작일 전이라는 이유는 데이터 문제가 아니다 (화면의 판단과 같은 기준). */
    private static final String NOT_A_DATA_PROBLEM = "GOAL_STARTED";

    private NotificationMessages() {
    }

    /** 주문안 만들기 결과에 대한 알림. 조용히 넘어가도 되는 결과면 empty. */
    public static Optional<String> forPlan(CreateResult r) {
        return switch (r.outcome()) {
            case SENT -> Optional.of(sent(r.plan()));
            case REJECTED -> Optional.of(rejected(r));
            case OPEN_EXISTS -> Optional.of(reminder(r.plan()));
            case NO_ACTION -> dataProblem(r.checks()) ? Optional.of(noActionWarning(r)) : Optional.empty();
            case ALREADY_TODAY -> Optional.empty();
        };
    }

    static String sent(PlanView p) {
        StringBuilder sb = new StringBuilder(TAG).append("주문안 #").append(p.id()).append(" 발송 (주문일 ")
                .append(p.tradeDate()).append(", ").append(p.expiresAt()).append("까지 유효)\n");
        if (p.note() != null && !p.note().isBlank()) {
            sb.append(p.note()).append('\n');
        }
        lines(sb, p.items(), "SELL", "매도");
        if (p.fxUsd() != null) {
            sb.append("- 환전: 원화 → $").append(p.fxUsd());
            if (p.fxKrwEst() != null) {
                sb.append(" (약 ").append(String.format("%,d", p.fxKrwEst())).append("원)");
            }
            sb.append('\n');
        }
        lines(sb, p.items(), "BUY", "매수");
        return sb.append("이 주문안대로 증권사 앱에서 주문한 뒤, 화면에서 체결 결과를 입력하세요.").toString();
    }

    private static void lines(StringBuilder sb, List<ItemView> items, String side, String label) {
        items.stream().filter(i -> i.side().equals(side)).forEach(i -> sb.append("- ").append(label).append(' ')
                .append(i.symbol()).append(' ').append(i.qty()).append("주 (예상 $")
                .append(price(i.estPrice())).append(")\n"));
    }

    private static String price(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String rejected(CreateResult r) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 안전 검증에 걸려 주문안");
        if (r.plan() != null) {
            sb.append(" #").append(r.plan().id());
        }
        sb.append("을 폐기했어요. 이 계산대로 주문하지 마세요.\n");
        failed(sb, r.checks());
        return sb.toString().stripTrailing();
    }

    static String reminder(PlanView p) {
        return TAG + "미완료 주문안 #" + p.id() + "이 있어요 (" + p.expiresAt() + "까지 유효).\n"
                + "아직 주문 전이면 이 주문안대로 주문하고, 이미 주문했다면 화면에서 체결 결과를 입력하세요.";
    }

    static String noActionWarning(CreateResult r) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 오늘은 주문이 없지만, 데이터 검증에 걸려 이 판단도 믿을 수 없어요.\n");
        failed(sb, r.checks());
        return sb.toString().stripTrailing();
    }

    private static void failed(StringBuilder sb, List<CheckView> checks) {
        checks.stream().filter(c -> !c.passed()).filter(c -> !NOT_A_DATA_PROBLEM.equals(c.rule()))
                .limit(MAX_LISTED).forEach(c -> sb.append("- ").append(c.label()).append(": ").append(c.detail()).append('\n'));
    }

    static boolean dataProblem(List<CheckView> checks) {
        return checks.stream().anyMatch(c -> !c.passed() && !NOT_A_DATA_PROBLEM.equals(c.rule()));
    }

    /** 수집에 오류가 있어 주문안을 만들지 않은 날. */
    public static String collectionErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 시세·환율 수집에 오류가 있어 오늘 주문안을 만들지 않았어요.\n");
        errors.stream().limit(MAX_LISTED).forEach(e -> sb.append("- ").append(e).append('\n'));
        if (errors.size() > MAX_LISTED) {
            sb.append("… 외 ").append(errors.size() - MAX_LISTED).append("건\n");
        }
        return sb.append("원인을 해결한 뒤 화면에서 직접 주문안을 만들 수 있어요.").toString();
    }

    /** 작업이 예외로 멈췄을 때. 예외 메시지는 길이만 제한해 담는다. */
    public static String failure(String what, RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        return TAG + "[주의] " + what + " 중 오류로 멈췄어요: " + e.getClass().getSimpleName()
                + (msg.isEmpty() ? "" : " - " + msg);
    }
}

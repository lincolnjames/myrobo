package com.myrobo.broker;

import com.myrobo.broker.DispatchStore.Phase;
import com.myrobo.broker.OrderPlanner.Order;
import com.myrobo.plan.PlanViews.PlanView;

import java.math.BigDecimal;
import java.util.List;

/** 증권사 전송 알림 문구. 종목·수량·지정가까지만 담고 잔고·합계 금액·계좌번호는 넣지 않는다. */
final class DispatchMessages {

    private static final String TAG = "[myrobo] ";
    private static final String SELL_FIRST = "현금이 모자라 매도만 먼저 예약해요. 매수는 매도 대금이 들어온 뒤 다음 거래일에 예약해요.";

    private DispatchMessages() {
    }

    static String dryRun(PlanView p, List<Order> orders, Phase phase) {
        StringBuilder sb = new StringBuilder(TAG).append("드라이런: 주문안 #").append(p.id())
                .append("을 이렇게 예약주문할 예정이에요 (실제로 보내지 않음)\n");
        phaseNote(sb, phase);
        lines(sb, orders);
        return sb.append("이대로 괜찮으면 ROBO_BROKER_MODE를 paper로 바꿔 모의투자로 접수해 볼 수 있어요.").toString();
    }

    static String submitted(PlanView p, List<Order> orders, Phase phase) {
        StringBuilder sb = new StringBuilder(TAG).append("모의투자 예약주문 접수: 주문안 #").append(p.id())
                .append(" ").append(orders.size()).append("건. 오늘 밤 미국 장 시작 때 전송되고, 체결되지 않으면 장 마감 뒤 자동 취소돼요.\n");
        phaseNote(sb, phase);
        lines(sb, orders);
        return sb.toString().stripTrailing();
    }

    static String failed(PlanView p, Order failed, String why, List<Order> cancelled, List<Order> stillOpen, int notSent) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 주문안 #").append(p.id()).append(" 예약주문 중 ")
                .append(failed.symbol()).append(' ').append(OrderDispatcher.label(failed.side())).append(" 접수 실패: ")
                .append(why).append('\n');
        if (!cancelled.isEmpty()) {
            sb.append("일부만 실행되지 않게, 먼저 접수된 주문을 취소했어요:\n");
            lines(sb, cancelled);
        }
        if (!stillOpen.isEmpty()) {
            sb.append("[중요] 아래 주문은 취소하지 못했어요. 증권사 앱에서 직접 취소하세요:\n");
            lines(sb, stillOpen);
        }
        if (notSent > 0) {
            sb.append("나머지 ").append(notSent).append("건은 보내지 않았어요.\n");
        }
        return sb.append("자동으로 다시 보내지 않아요.").toString();
    }

    static String blocked(PlanView p, List<String> problems) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 주문안 #").append(p.id())
                .append("을 증권사로 보내지 않았어요:\n");
        problems.stream().limit(10).forEach(x -> sb.append("- ").append(x).append('\n'));
        return sb.append("자동으로 다시 보내지 않아요.").toString();
    }

    static String retryLater(PlanView p, String why) {
        return TAG + "[주의] 주문안 #" + p.id() + " 예약주문 준비 중 문제가 있어 잠시 뒤 다시 시도해요: " + why;
    }

    /** 금액은 알림에 넣지 않는다 (매수 가능 금액은 잔고와 같다). */
    static String waitingFunds(PlanView p, BigDecimal need, BigDecimal have) {
        return TAG + "주문안 #" + p.id() + ": 매도는 접수했지만 매수 가능 금액이 아직 매수 합계보다 적어 매수를 기다려요. "
                + "매도가 체결되지 않았을 수 있어요. 주문안 유효기한 " + p.expiresAt() + "까지 다시 확인해요.";
    }

    private static void phaseNote(StringBuilder sb, Phase phase) {
        if (phase == Phase.SELL) {
            sb.append(SELL_FIRST).append('\n');
        } else if (phase == Phase.BUY) {
            sb.append("앞서 예약한 매도에 이어 매수를 예약해요.\n");
        }
    }

    private static void lines(StringBuilder sb, List<Order> orders) {
        orders.forEach(o -> sb.append("- ").append(OrderDispatcher.label(o.side())).append(' ').append(o.symbol())
                .append(' ').append(o.qty()).append("주 지정가 $").append(OrderPlanner.money(o.limitPrice()))
                .append(" (").append(o.exchange()).append(")\n"));
    }
}

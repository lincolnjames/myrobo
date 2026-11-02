package com.myrobo.broker;

import com.myrobo.broker.OrderPlanner.Order;
import com.myrobo.plan.PlanViews.PlanView;

import java.util.List;

/** 증권사 전송 알림 문구. 종목·수량·지정가까지만 담고 합계 금액·계좌번호는 넣지 않는다. */
final class DispatchMessages {

    private static final String TAG = "[myrobo] ";

    private DispatchMessages() {
    }

    static String dryRun(PlanView p, List<Order> orders) {
        StringBuilder sb = new StringBuilder(TAG).append("드라이런: 주문안 #").append(p.id())
                .append("을 이렇게 예약주문할 예정이에요 (실제로 보내지 않음)\n");
        lines(sb, orders);
        return sb.append("이대로 괜찮으면 ROBO_BROKER_MODE를 paper로 바꿔 모의투자로 접수해 볼 수 있어요.").toString();
    }

    static String submitted(PlanView p, List<Order> orders) {
        StringBuilder sb = new StringBuilder(TAG).append("모의투자 예약주문 접수: 주문안 #").append(p.id())
                .append(" ").append(orders.size()).append("건. 오늘 밤 미국 장 시작 때 전송되고, 체결되지 않으면 장 마감 뒤 자동 취소돼요.\n");
        lines(sb, orders);
        return sb.toString().stripTrailing();
    }

    static String failed(PlanView p, List<Order> accepted, Order failed, String why, int notSent) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 주문안 #").append(p.id()).append(" 예약주문 중 ")
                .append(failed.symbol()).append(' ').append(OrderDispatcher.label(failed.side())).append(" 접수 실패: ")
                .append(why).append('\n');
        if (!accepted.isEmpty()) {
            sb.append("이미 접수된 주문 (증권사 앱에서 확인하세요):\n");
            lines(sb, accepted);
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

    private static void lines(StringBuilder sb, List<Order> orders) {
        orders.forEach(o -> sb.append("- ").append(OrderDispatcher.label(o.side())).append(' ').append(o.symbol())
                .append(' ').append(o.qty()).append("주 지정가 $").append(OrderPlanner.money(o.limitPrice()))
                .append(" (").append(o.exchange()).append(")\n"));
    }
}

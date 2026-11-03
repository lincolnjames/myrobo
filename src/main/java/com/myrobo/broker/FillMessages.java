package com.myrobo.broker;

import com.myrobo.broker.FillReconciler.Matched;
import com.myrobo.plan.PlanViews.ItemView;
import com.myrobo.plan.PlanViews.PlanView;

import java.util.List;

/** 체결 반영 알림 문구. 종목·수량·단가까지만 담고 현금·합계 금액·계좌번호는 넣지 않는다. */
final class FillMessages {

    private static final String TAG = "[myrobo] ";

    private FillMessages() {
    }

    static String settled(PlanView p, List<Matched> matched, List<ItemView> notSent) {
        StringBuilder sb = new StringBuilder(TAG).append("모의투자 체결 반영: 주문안 #").append(p.id())
                .append("을 완료 처리하고 잔고를 갱신했어요 (증권사 잔고와 수량 일치).\n");
        for (Matched x : matched) {
            long q = x.fill().filledQty();
            sb.append("- ").append(OrderDispatcher.label(x.order().side())).append(' ').append(x.order().symbol())
                    .append(' ');
            if (q == 0) {
                sb.append(x.order().qty()).append("주 미체결\n");
            } else {
                sb.append(q).append('/').append(x.order().qty()).append("주 체결, 평균 $")
                        .append(FillReconciler.price(x.fill().filledPrice()).toPlainString()).append('\n');
            }
        }
        for (ItemView i : notSent) {
            sb.append("- ").append("SELL".equals(i.side()) ? "매도 " : "매수 ").append(i.symbol()).append(' ')
                    .append(i.qty()).append("주는 접수되지 않아 미체결로 처리\n");
        }
        return sb.toString().stripTrailing();
    }

    static String stopped(PlanView p, List<String> problems) {
        StringBuilder sb = new StringBuilder(TAG).append("[주의] 주문안 #").append(p.id())
                .append(" 체결을 자동으로 반영하지 못했어요 (아무것도 저장하지 않음):\n");
        problems.stream().limit(10).forEach(x -> sb.append("- ").append(x).append('\n'));
        return sb.append("증권사 앱에서 체결과 잔고를 확인하고, 화면에서 체결을 직접 입력하세요. ")
                .append("그대로 두면 주문안은 유효기한(").append(p.expiresAt()).append(") 다음 거래일이 지나 만료돼요.")
                .toString();
    }

    static String retryLater(PlanView p, String why) {
        return TAG + "[주의] 주문안 #" + p.id() + " 체결 확인 중 문제가 있어 잠시 뒤 다시 시도해요: " + why;
    }
}

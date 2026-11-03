package com.myrobo.broker;

import com.myrobo.broker.OrderPlanner.Order;
import com.myrobo.broker.OrderPlanner.Side;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 전송 기록 (broker_dispatch, broker_order). 테스트에서는 메모리 가짜로 바꿔 끼운다. */
public interface DispatchStore {

    enum Mode { DRY_RUN, PAPER }

    /** ALL: 한 번에 전부. SELL → BUY: 현금이 모자라 매도를 먼저 예약하고 다음 거래일에 매수. */
    enum Phase { ALL, SELL, BUY }

    /** 지난 시도 하나. day는 시도한 한국 날짜. */
    record Attempt(long id, Phase phase, String status, LocalDate day) {
    }

    /** 이 주문안을 이 모드로 보낸 시도들. */
    List<Attempt> attempts(long planId, Mode mode);

    /** 시도 기록을 PENDING으로 먼저 남긴다. 이미 있으면(동시에 다른 쪽이 시작) empty → 보내지 않는다. */
    Optional<Long> start(long planId, Mode mode, Phase phase);

    /** 주문 줄 하나를 기록하고 id를 돌려준다. status는 PLANNED(드라이런) 또는 SENDING(보내기 직전). */
    long addOrder(long dispatchId, Order o, String status);

    /** 접수 결과. status: SUBMITTED 또는 FAILED. */
    void orderResult(long orderId, String status, KisClient.Reply reply);

    /** 접수됐던 줄의 취소 결과. status: CANCELLED 또는 CANCEL_FAILED. 접수 때의 주문번호·응답은 그대로 둔다. */
    void cancelResult(long orderId, String status, KisClient.Reply reply);

    void finish(long dispatchId, String status, String message);

    /** 그 한국 날짜에 실제로 접수된(SUBMITTED) 주문 합계. 드라이런은 세지 않는다. */
    BigDecimal submittedUsdOn(LocalDate kstDay);

    /**
     * 증권사에서 실행됐을 수 있는 주문 줄: 접수됨(SUBMITTED)이나 취소 실패(CANCEL_FAILED) 중 체결 반영 전인 것.
     *
     * @param orderDate 접수한 한국 날짜 = 그 밤에 열리는 장의 뉴욕 날짜 = 체결내역의 주문일
     */
    record LiveOrder(long id, long itemId, Side side, String symbol, String exchange, long qty, BigDecimal limitPrice,
                     LocalDate orderDate) {
    }

    List<LiveOrder> liveOrders(long planId, Mode mode);

    /** 체결 반영 결과 한 줄. fillStatus: FILLED, PARTIAL, UNFILLED. 체결이 없으면 주문번호·가격·시각은 null. */
    record FillRecord(long orderId, String fillStatus, String fillOrderNo, long filledQty, BigDecimal filledPrice,
                      OffsetDateTime filledAt) {
    }

    void recordFills(List<FillRecord> fills);
}

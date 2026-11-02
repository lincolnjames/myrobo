package com.myrobo.broker;

import com.myrobo.broker.OrderPlanner.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** 전송 기록 (broker_dispatch, broker_order). 테스트에서는 메모리 가짜로 바꿔 끼운다. */
public interface DispatchStore {

    enum Mode { DRY_RUN, PAPER }

    /** 이 주문안을 이 모드로 이미 보내 봤으면 그때 상태. */
    Optional<String> status(long planId, Mode mode);

    /** 시도 기록을 PENDING으로 먼저 남긴다. 이미 있으면(동시에 다른 쪽이 시작) empty → 보내지 않는다. */
    Optional<Long> start(long planId, Mode mode);

    /** 주문 줄 하나를 기록하고 id를 돌려준다. status는 PLANNED(드라이런) 또는 SENDING(보내기 직전). */
    long addOrder(long dispatchId, Order o, String status);

    void orderResult(long orderId, String status, KisClient.Reply reply);

    void finish(long dispatchId, String status, String message);

    /** 그 한국 날짜에 실제로 접수된(SUBMITTED) 주문 합계. 드라이런은 세지 않는다. */
    BigDecimal submittedUsdOn(LocalDate kstDay);
}

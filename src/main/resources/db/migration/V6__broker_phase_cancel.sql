-- 주문안을 두 단계로 보낼 수 있게: 현금이 모자라면 매도(SELL)를 먼저 예약하고, 다음 거래일에 매수(BUY).
-- 현금이 충분하면 지금처럼 한 번에(ALL). 시도는 주문안·모드·단계마다 한 번 (unique).
-- 한 줄이라도 접수에 실패하면 앞서 접수된 예약을 취소한다: CANCELLED, 취소도 실패하면 CANCEL_FAILED(사람이 직접 취소).
alter table broker_dispatch add column phase text not null default 'ALL' check (phase in ('ALL', 'SELL', 'BUY'));
alter table broker_dispatch drop constraint broker_dispatch_plan_id_mode_key;
alter table broker_dispatch add constraint broker_dispatch_plan_mode_phase_key unique (plan_id, mode, phase);

alter table broker_order drop constraint broker_order_status_check;
alter table broker_order add constraint broker_order_status_check
    check (status in ('PLANNED', 'SENDING', 'SUBMITTED', 'FAILED', 'CANCELLED', 'CANCEL_FAILED'));

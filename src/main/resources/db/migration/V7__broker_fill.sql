-- 체결 반영: 접수된 예약주문이 장에서 어떻게 됐는지 (주문체결내역 inquire-ccnl로 확인).
-- 예약주문은 장 시작에 새 주문번호로 나가므로(2026-11-03 모의: 예약 267 → 주문 36617) 그 번호를 따로 남긴다.
-- fill_status가 비어 있는 접수 줄은 "아직 반영 전"이라는 뜻이고, 그동안은 주문안을 바로 만료시키지 않는다.
alter table broker_order add column fill_status   text check (fill_status in ('FILLED', 'PARTIAL', 'UNFILLED'));
alter table broker_order add column fill_order_no text;
alter table broker_order add column filled_qty    bigint check (filled_qty >= 0);
alter table broker_order add column filled_price  numeric(18, 6);
alter table broker_order add column filled_at     timestamptz;

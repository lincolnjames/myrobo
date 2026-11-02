-- 증권사 API로 주문안을 보낸 기록.
-- broker_dispatch: 주문안 하나를 한 모드(DRY_RUN·PAPER·LIVE)로 보내는 시도는 단 한 번 (unique). 이 행을 먼저 넣고 나서
--   주문을 보내므로, 앱이 두 개 떠 있거나 같은 틱이 겹쳐도 같은 주문안이 두 번 전송되지 않는다.
--   실패·차단돼도 자동으로 다시 보내지 않는다 (사람이 확인).
-- broker_order: 그 시도에서 만든 주문 줄. 보내기 전에 SENDING으로 저장하고 응답을 받으면 결과로 바꾼다.
--   앱이 전송 중에 죽으면 SENDING으로 남아 증권사 쪽 상태를 사람이 확인해야 한다는 표시가 된다.
create table broker_dispatch (
    id         bigint generated always as identity primary key,
    plan_id    bigint      not null references order_plan (id),
    mode       text        not null check (mode in ('DRY_RUN', 'PAPER', 'LIVE')),
    status     text        not null check (status in ('PENDING', 'DRY_RUN', 'SUBMITTED', 'FAILED', 'BLOCKED')),
    message    text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (plan_id, mode)
);

create table broker_order (
    id              bigint generated always as identity primary key,
    dispatch_id     bigint         not null references broker_dispatch (id),
    plan_item_id    bigint         not null references order_plan_item (id),
    side            text           not null check (side in ('BUY', 'SELL')),
    symbol          text           not null,
    exchange        text           not null,
    qty             bigint         not null check (qty > 0),
    limit_price     numeric(18, 4) not null check (limit_price > 0),
    status          text           not null check (status in ('PLANNED', 'SENDING', 'SUBMITTED', 'FAILED')),
    broker_order_no text,                       -- 증권사 예약주문번호 (취소할 때 필요)
    broker_order_dt text,                       -- 증권사 예약주문 접수일자
    rt_cd           text,
    msg_cd          text,
    message         text,
    response        text,                       -- 응답 본문 원문 (키·토큰은 응답에 없다)
    created_at      timestamptz    not null default now(),
    updated_at      timestamptz    not null default now(),
    unique (dispatch_id, plan_item_id)
);

alter table broker_dispatch enable row level security;
alter table broker_order    enable row level security;

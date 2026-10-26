-- 미국 시장 휴장일 (Finnhub). 거래일 계산에 쓴다.
-- full_day_closed = false는 조기 폐장일(거래일)이다.
create table market_holiday (
    exchange        text        not null default 'US',
    date            date        not null,
    event_name      text        not null,
    trading_hours   text,
    full_day_closed boolean     not null,
    updated_at      timestamptz not null default now(),
    primary key (exchange, date)
);

alter table market_holiday enable row level security;

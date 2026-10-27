-- 일별 평가액과 낙폭.
-- 낙폭은 평가액이 아니라 "지수"로 잰다: 직전 기록 시점의 보유 수량·현금을 지금 가격·환율로 다시 평가해
-- 그 비율만 이어 붙인다(시작 100). 입출금과 매매는 수익률에 들어가지 않아, 출금이 하락으로 잡히지 않는다.
-- 기준 통화는 원화(환율 변동 포함). 날짜는 한국 날짜이고 하루 한 줄, 다시 계산하면 덮어쓴다.
create table valuation_daily (
    date         date           primary key,
    total_krw    numeric(20, 2) not null,
    return_pct   numeric(12, 6),                          -- 직전 기록 대비. 첫 기록은 null
    nav          numeric(20, 8) not null check (nav > 0),
    peak_nav     numeric(20, 8) not null check (peak_nav > 0),
    peak_date    date           not null,
    drawdown_pct numeric(8, 4)  not null check (drawdown_pct >= 0),
    alert_level  int            not null default 0 check (alert_level >= 0),
    computed_at  timestamptz    not null default now()
);

alter table valuation_daily enable row level security;

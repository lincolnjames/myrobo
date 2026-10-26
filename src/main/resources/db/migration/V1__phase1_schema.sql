-- 1단계 스키마: 목표·계좌·잔고·현금·시세·환율·주문안·실행 기록·규칙 승인.
-- 뉴스·공시·신호·추천·스트레스 결과 테이블은 해당 기능을 만들 때 V2 이후에 추가한다.
--
-- 모든 테이블은 robo 스키마에 둔다. Supabase는 public 스키마를 Data API(REST)로 공개하므로,
-- 공개되지 않는 별도 스키마에 두고 앱은 Postgres로 직접 접속한다.
-- 금액은 numeric(정확한 십진수), 주수는 bigint. float는 쓰지 않는다.

create table goal (
    id               bigint generated always as identity primary key,
    name             text        not null,
    horizon          text        not null check (horizon in ('1M', '3M', '6M', '12M', '36M', 'OPEN')),
    start_date       date        not null,
    max_drawdown_pct numeric(5, 2) not null check (max_drawdown_pct > 0 and max_drawdown_pct <= 100),
    base_currency    char(3)     not null default 'KRW',
    created_at       timestamptz not null default now()
);

create table account (
    id      bigint generated always as identity primary key,
    broker  text    not null check (broker in ('SAMSUNG', 'SHINHAN')),
    role    text    not null check (role in ('CORE', 'SATELLITE')),
    goal_id bigint  references goal (id),
    name    text    not null,
    active  boolean not null default true
);

create table instrument (
    symbol      text primary key,
    type        text    not null check (type in ('ETF', 'STOCK')),
    asset_class text,
    sector      text,
    sleeve      text    not null check (sleeve in ('CORE', 'SATELLITE', 'WATCH')),
    active      boolean not null default true
);

-- 직접 입력한 잔고. 화이트리스트 밖 종목도 기록할 수 있게 instrument를 참조하지 않는다.
create table holding_snapshot (
    account_id bigint  not null references account (id),
    as_of      date    not null,
    symbol     text    not null,
    qty        bigint  not null check (qty >= 0),
    avg_price  numeric(18, 6),
    primary key (account_id, as_of, symbol)
);

create table cash_balance (
    account_id bigint        not null references account (id),
    as_of      date          not null,
    currency   char(3)       not null check (currency in ('KRW', 'USD')),
    amount     numeric(20, 2) not null,
    primary key (account_id, as_of, currency)
);

create table cash_ledger (
    id         bigint generated always as identity primary key,
    account_id bigint        not null references account (id),
    date       date          not null,
    type       text          not null check (type in ('DEPOSIT', 'WITHDRAWAL', 'DIVIDEND', 'FX', 'FEE', 'TAX')),
    currency   char(3)       not null check (currency in ('KRW', 'USD')),
    amount     numeric(20, 2) not null,
    memo       text
);

create table fx_rate (
    date   date          not null,
    pair   text          not null check (pair in ('USD/KRW')),
    rate   numeric(12, 4) not null check (rate > 0),
    source text          not null,
    primary key (date, pair)
);

-- 일봉. 신호 계산은 adj_close(수정주가)를 쓴다. 같은 날 다시 받아도 upsert라 중복이 생기지 않는다.
create table price_daily (
    symbol    text          not null,
    date      date          not null,
    open      numeric(18, 6),
    high      numeric(18, 6),
    low       numeric(18, 6),
    close     numeric(18, 6) not null,
    adj_close numeric(18, 6) not null,
    volume    bigint,
    source    text          not null,
    primary key (symbol, date)
);

create table corporate_action (
    symbol  text           not null,
    ex_date date           not null,
    type    text           not null check (type in ('SPLIT', 'DIVIDEND')),
    ratio   numeric(18, 8),
    amount  numeric(18, 6),
    primary key (symbol, ex_date, type)
);

-- 규칙(설정 파일) 승인 기록. 승인된 해시와 다른 설정 파일로는 앱이 시작하지 않는다.
create table rule_change (
    id              bigint generated always as identity primary key,
    version         text        not null,
    kind            text        not null check (kind in ('BUGFIX', 'STRICTER', 'LOOSER', 'POLICY')),
    proposed_at     timestamptz not null default now(),
    reason          text        not null,
    expected_effect text,
    backtest_ok     boolean,
    stress_ok       boolean,
    applies_from    date,
    status          text        not null check (status in ('PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    config_hash     char(64)    not null,
    approved_at     timestamptz
);
create unique index rule_change_approved_hash on rule_change (config_hash) where status = 'APPROVED';

create table order_plan (
    id              bigint generated always as identity primary key,
    account_id      bigint        not null references account (id),
    plan_date       date          not null,
    type            text          not null check (type in ('CORE', 'SATELLITE', 'INITIAL_BUILD', 'PROFILE_SHIFT', 'TAX_LOSS')),
    status          text          not null check (status in ('DRAFT', 'REJECTED', 'SENT', 'DONE', 'EXPIRED')),
    idempotency_key text          not null unique,  -- 날짜 + 종류 + 규칙 버전: 같은 날 두 번 돌아도 1건
    rule_version    text          not null,
    action          text          not null,
    reason          text          not null,
    total_amount    numeric(20, 2),
    size_pct        numeric(6, 2),
    fx_usd          bigint,
    fx_krw_est      bigint,
    expires_at      date,
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now()
);
-- 미완료 주문안은 계좌당 하나만: 같은 걸 두 번 사는 사고를 DB 차원에서도 막는다
create unique index order_plan_one_open_per_account on order_plan (account_id) where status in ('DRAFT', 'SENT');

create table order_plan_item (
    id         bigint generated always as identity primary key,
    plan_id    bigint         not null references order_plan (id),
    side       text           not null check (side in ('BUY', 'SELL')),
    symbol     text           not null,
    qty        bigint         not null check (qty > 0),
    est_price  numeric(18, 6) not null,
    est_amount numeric(20, 2) not null,
    currency   char(3)        not null default 'USD',
    reason     text
);

create table guard_result (
    plan_id bigint  not null references order_plan (id),
    rule    text    not null,
    passed  boolean not null,
    detail  text,
    primary key (plan_id, rule)
);

create table execution (
    id           bigint generated always as identity primary key,
    plan_item_id bigint         not null references order_plan_item (id),
    executed_at  timestamptz    not null,
    qty          bigint         not null check (qty > 0),
    price        numeric(18, 6) not null,
    fee          numeric(18, 6) not null default 0,
    currency     char(3)        not null default 'USD'
);

create table job_run (
    id          bigint generated always as identity primary key,
    job         text        not null,
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    status      text        not null check (status in ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    error       text
);

-- 혹시 스키마가 Data API에 노출되더라도 anon/authenticated 역할로는 읽을 수 없게 RLS를 켠다.
-- 앱은 postgres 역할로 직접 접속하므로 영향이 없다.
-- 대상은 이 파일이 만든 테이블만 명시한다. 스키마 전체를 반복하면 Flyway가 마이그레이션 중
-- 붙잡고 있는 flyway_schema_history까지 잠그려다 대기 → 타임아웃이 난다.
alter table goal             enable row level security;
alter table account          enable row level security;
alter table instrument       enable row level security;
alter table holding_snapshot enable row level security;
alter table cash_balance     enable row level security;
alter table cash_ledger      enable row level security;
alter table fx_rate          enable row level security;
alter table price_daily      enable row level security;
alter table corporate_action enable row level security;
alter table rule_change      enable row level security;
alter table order_plan       enable row level security;
alter table order_plan_item  enable row level security;
alter table guard_result     enable row level security;
alter table execution        enable row level security;
alter table job_run          enable row level security;

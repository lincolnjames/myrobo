-- 체결 입력이 쓰는 SQL을 실제 Postgres에서 한 번 돌려 보는 확인용 스크립트.
-- Supabase 대시보드 > SQL Editor에 통째로 붙여 넣고 Run 한다.
--
-- 안전: 맨 끝에서 일부러 예외를 던져 트랜잭션 전체가 롤백된다. 아무 데이터도 남지 않는다.
--       "ROLLBACK OK"로 시작하는 오류 메시지가 보이면 성공이다 (실패 시에는 다른 오류 메시지가 나온다).
-- 주의: 미완료(DRAFT/SENT) 주문안이 이미 있으면 부분 유니크 인덱스에 걸려 시작 단계에서 실패한다.
--       그건 스크립트 문제가 아니라 정상 보호 장치이니, 주문안이 없을 때 다시 돌린다.
--
-- 확인하는 것: 앱 코드(PlanRepository, HoldingsRepository)와 같은 문장이 스키마에 맞게 실행되는지,
--             주문안을 닫은 시각(now()) 뒤에 잔고 저장 시각(clock_timestamp())이 찍히는지.

set search_path to robo;

do $$
declare
    acct       bigint;
    plan_id    bigint;
    item_id    bigint;
    n          int;
    closed_at  timestamptz;
    entered    timestamptz;
    ledger_sum numeric;
begin
    select id into acct from account where role = 'CORE' and active order by id limit 1;
    if acct is null then
        raise exception '코어 계좌가 없음. 앱을 한 번 실행해 기준 데이터를 만든 뒤 다시 돌리세요';
    end if;

    -- 주문안 + 줄 (SENT 상태로 만든다)
    insert into order_plan (account_id, plan_date, type, status, idempotency_key, rule_version, action, reason,
                            fx_usd, fx_krw_est, expires_at)
    values (acct, current_date, 'CORE', 'SENT', 'verify-' || clock_timestamp()::text, 'verify', 'REBALANCE',
            'VERIFY', 6000, 7800000, current_date + 3)
    returning id into plan_id;
    insert into order_plan_item (plan_id, side, symbol, qty, est_price, est_amount, currency)
    values (plan_id, 'BUY', 'VTI', 10, 373.01, 3730.10, 'USD')
    returning id into item_id;

    -- PlanRepository.SET_STATUS: SENT → DONE (updated_at = now())
    update order_plan set status = 'DONE', updated_at = now() where id = plan_id and status = 'SENT';
    get diagnostics n = row_count;
    if n <> 1 then raise exception 'SENT → DONE 전이가 1건이 아님 (%)', n; end if;

    -- 같은 전이를 다시 하면 0건이어야 한다 (이중 입력 방지)
    update order_plan set status = 'DONE', updated_at = now() where id = plan_id and status = 'SENT';
    get diagnostics n = row_count;
    if n <> 0 then raise exception '이미 DONE인데 전이가 다시 됨 (%)', n; end if;

    -- PlanRepository.INSERT_EXECUTION
    insert into execution (plan_item_id, executed_at, qty, price, fee, currency)
    values (item_id, now(), 10, 373.01, 0.50, 'USD');

    -- HoldingsRepository.INSERT_LEDGER (환전 두 줄)
    insert into cash_ledger (account_id, date, type, currency, amount, memo)
    values (acct, current_date, 'FX', 'USD', 6000.00, 'verify'),
           (acct, current_date, 'FX', 'KRW', -7878000.00, 'verify');
    select sum(amount) into ledger_sum from cash_ledger where memo = 'verify' and currency = 'USD';

    -- HoldingsRepository.UPSERT_CASH_NOW (기존 기록을 건드리지 않게 먼 미래 날짜로, 두 번 호출해 갱신 경로도 확인)
    insert into cash_balance (account_id, as_of, currency, amount, entered_at)
    values (acct, date '2099-01-01', 'USD', 686.50, clock_timestamp())
    on conflict (account_id, as_of, currency) do update
    set amount = excluded.amount, entered_at = clock_timestamp();
    insert into cash_balance (account_id, as_of, currency, amount, entered_at)
    values (acct, date '2099-01-01', 'USD', 686.50, clock_timestamp())
    on conflict (account_id, as_of, currency) do update
    set amount = excluded.amount, entered_at = clock_timestamp();

    select updated_at into closed_at from order_plan where id = plan_id;
    select max(entered_at) into entered from cash_balance where account_id = acct and as_of = date '2099-01-01';

    -- 핵심: 잔고 저장 시각이 주문안 닫은 시각보다 "나중"이어야 HOLDINGS_AFTER_LAST_PLAN을 통과한다
    if not (entered > closed_at) then
        raise exception 'FAIL: 잔고 저장 시각(%)이 주문안 닫은 시각(%)보다 늦지 않음', entered, closed_at;
    end if;

    -- 일부러 예외: 지금까지 한 모든 일을 롤백한다
    raise exception 'ROLLBACK OK — 전이 1건, 재전이 0건, 환전 장부 USD 합 %, 닫은 시각 %, 잔고 저장 시각 % (더 늦음). 데이터는 남지 않았음',
        ledger_sum, closed_at, entered;
end $$;

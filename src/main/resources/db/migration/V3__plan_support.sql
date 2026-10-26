-- 주문안 지원.
-- 잔고를 언제 저장했는지 기록한다. 주문안이 만료·완료된 뒤에는 그보다 나중에 입력한 잔고로만
-- 새 주문안을 만든다 (일부만 체결된 주문안을 옛 잔고로 다시 계산해 같은 주문을 또 내는 사고 방지).
-- 기존 행은 마이그레이션 시각으로 채워진다.
alter table cash_balance add column entered_at timestamptz not null default now();

-- 주문안 목록·만료 처리용
create index order_plan_account_created on order_plan (account_id, created_at desc);

-- 결정 설명 (예: "필요한 매매가 총자산의 82%라 25%씩 매주 나눠 진행")
alter table order_plan add column note text;

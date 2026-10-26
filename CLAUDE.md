# myrobo — 작업 안내 (Claude Code용)

본인 한 명이 자기 돈으로 쓰는 개인용 로보어드바이저. 코어-위성 자산배분, 미국 시장만.
증권사 API가 없어서 1단계는 반자동: 시스템이 주문안을 계산하고, 사람이 삼성증권 앱에서 직접 주문한다.

- 사용자: 신입 백엔드 개발자(Java/Spring). 설명과 커밋 메시지는 **한국어**. Windows + PowerShell
- 우선순위: **1) 주문 안전성 2) 수익률 3) 법적 문제 없음**. 애매하면 더 안전한 쪽으로
- 설계 문서: README의 아키텍처·알고리즘 명세 링크

## 명령

```powershell
.\gradlew.bat test      # DB·네트워크 없이 도는 테스트 전부 (현재 87개 = 단위 72 + 골든 15)
.\gradlew.bat bootRun   # 환경변수 DB_URL, DB_USER, DB_PASSWORD, TIINGO_KEY, FINNHUB_KEY 필요 (KOREAEXIM_KEY는 선택)
```

- 화면: http://localhost:8080 (127.0.0.1에만 바인딩)
- 수동 수집: `Invoke-RestMethod -Method Post -Headers @{'X-Robo-Request'='1'} http://localhost:8080/internal/jobs/collect`
- 콘솔 한글이 깨지면 `chcp 65001`
- 스택: Java 17(사용자 JDK는 26이라 Gradle 9.4.1 필요), Spring Boot 4.1, Supabase Postgres 17(Session pooler), Flyway

## 패키지

| 패키지 | 역할 | Spring 의존 |
| --- | --- | --- |
| `portfolio` | 리밸런싱 엔진 `RebalancePlanner`, 비중 평가 `Valuation` | 없음 (순수) |
| `guard` | 안전 검증 `SafetyGuard` (규칙 11개) | 없음 (순수) |
| `plan` | 주문 일정, 분할 결정 `PlanDecider`, 주문안 저장·상태 | 있음 |
| `holdings` | 잔고 스냅샷 입력·저장, 설정→DB 기준 데이터 동기화 | 있음 |
| `collector` | Tiingo 일봉, Finnhub 휴장일, 환율(수출입은행→Frankfurter), `MarketCalendar` | 수집 로직은 없음 |
| `common.config` | `robo-config.yml` 로딩·검증·해시 승인 | 일부 |
| `web` | REST 컨트롤러, 로컬 전용 보호 필터 | 있음 |
| `ops` | 배치 기록 job_run, 수동 실행 | 있음 |

## 지켜야 할 규칙

- **규칙의 원본은 `config/robo-config.yml` 하나.** 코드에 숫자를 하드코딩하지 않는다
- **설정 파일을 함부로 고치지 않는다.** 앱이 파일 SHA-256을 DB `rule_change`의 승인 기록과 비교해 다르면 시작을 거부한다.
  현재 승인 해시 `86fe315b…` (version 2026.10.1). 실행 설정은 `application.yml`에 둔다
- **리밸런싱 규칙을 바꿀 때**: `tools/rebalance_ref.py`를 먼저 고치고 → `cd tools; python gen_cases.py`로 `golden_cases.json`을 다시 만든 뒤 → Java를 맞춘다.
  **정답 JSON이나 테스트 기대값을 손으로 고치지 않는다**
- **금액은 BigDecimal / numeric.** float·double 금지. 주수는 long/bigint
- **안전 검증은 엔진 결과를 믿지 않고 입력에서 다시 계산한다.** 하나라도 어기면 주문안 전체 폐기(REJECTED). 일부만 실행하는 경로를 만들지 않는다
- **주문안 상태**: DRAFT → SENT(검증 통과) / REJECTED(실패). SENT → DONE(체결 입력) / EXPIRED(3거래일 경과). 상태 전이는 `PlanRepository.transition(id, from, to)`로만
- **미완료 주문안은 계좌당 하나, 하루 한 건**: 계좌 advisory lock + 부분 유니크 인덱스 + idempotency_key. 이 장치들을 우회하지 않는다
- **비밀값은 환경변수로만.** 저장소가 public이다. 키·비밀번호를 파일·로그·커밋에 넣지 않는다
- **DB는 `robo` 스키마 + 모든 테이블 RLS.** 마이그레이션은 이미 적용된 파일(V1~V3)을 고치지 않고 다음 번호(V4…)로 추가한다.
  RLS를 켤 때 스키마 전체 반복문을 쓰지 않는다 (Flyway가 잡고 있는 `flyway_schema_history`에 막혀 타임아웃)
- **변경 요청(POST/PUT/DELETE)은 `X-Robo-Request: 1` 헤더 필수**, Host는 localhost만 (`LocalRequestGuard`)
- 한국 날짜(Asia/Seoul)와 미국 거래일(America/New_York)을 섞지 않는다. 한국 날짜 D에 만든 주문안은 뉴욕 거래일 D(휴장이면 다음 거래일) 장에서 넣는다고 본다 (`TradingSchedule`)

## 겪었던 함정

- Java 텍스트 블록 두 개를 `+`로 이으면 둘째 블록 앞 공백이 사라진다 (`from order_planwhere`). 이어 붙일 땐 일반 문자열로
- SQL을 바꾸면 실제 Postgres에서 한 번 돌려 볼 것. 단위 테스트는 DB 없이 돈다
- JSON 응답에 한글이 깨지면 `produces = "application/json;charset=UTF-8"`
- Postgres `now()`는 트랜잭션 시작 시각이다. 같은 트랜잭션에서 주문안을 닫고(updated_at) 잔고를 저장하면(entered_at) 두 값이 같아져
  `HOLDINGS_AFTER_LAST_PLAN`(잔고가 닫힌 시각보다 **나중**이어야 함)에 걸린다. 체결 입력에서 주의

## 현재 상태 (2026-10-27)

- 완료: 엔진(골든 15개), 설정 승인, 데이터 수집, 잔고 입력 화면, 안전 검증, 주문안 만들기·저장·만료, 화면의 주문안 섹션
- 사용자 PC에서 Supabase에 V1~V3 적용, 테스트·화면 동작 확인 완료
- 목표 시작일 `goal.startDate` = **2026-11-02**. 그 전에는 주문안이 발송되지 않는다.
  현재 잔고(BIL 위주)는 필요한 매매가 163%라 11-02(월, 주·월 첫 거래일)부터 매주 25% 이내로 나눠 진행된다

## 체결 입력 (구현됨, 실제 Postgres 확인 전)

`POST /api/plans/{id}/executions` (`ExecutionService`, 순수 계산은 `ExecutionInput`). 아래 명세대로 구현했고 단위 테스트는 통과.
**새 SQL(`execution`·`cash_ledger` insert, `clock_timestamp()` 잔고 저장)은 아직 실제 DB에서 안 돌려 봄.** 11-02 전에 한 번 확인할 것.
추가로 정한 것: 주문안 생성 뒤에 직접 입력한 잔고가 있으면 이중 반영 위험이라 거부(409), 만료된 주문안은 받지 않음(수동 잔고 입력으로 처리), 미체결 줄은 `execution`에 저장하지 않음.

명세 (원문):

1. 발송(SENT) 주문안의 줄마다 실제 체결 수량·가격·수수료 입력 → `execution` 테이블. 수량은 0(미체결) 이상, 계획 수량 이하
2. 실제 환전(달러 받은 금액, 원화 쓴 금액) 입력 → `cash_ledger`(type FX). 예상 대비 실제 환율 차이를 기록해 1% 버퍼가 충분한지 본다
3. 저장하면 한 트랜잭션에서 주문안 SENT → DONE, 그리고 직전 잔고 ± 체결분으로 새 잔고 스냅샷(기준일 = 오늘 한국 날짜)을 만든다.
   위 `now()` 함정 때문에 순서·시각 처리를 정해야 함 (예: 체결로 만든 스냅샷은 닫힌 시각 이후로 인정되게 `clock_timestamp()` 사용)
4. 일부만 체결돼도 실제 체결분만 반영. 저장 뒤 화면에서 증권사 앱 잔고와 맞는지 확인하라고 안내
5. 테스트: 전량 체결, 일부 체결, 미체결, 계획보다 많이 입력(거부), SENT가 아닌 주문안(거부), 체결 뒤 바로 다음 주문안을 만들 수 있는지

그다음: 텔레그램 알림, 08:00 수집 뒤 주문안 자동 생성(`ops`), 외부 감시용 완료 신호, 주간 백업, 일별 평가액과 낙폭 경보.

## 커밋

- 커밋 메시지는 한국어로, 무엇을 왜 바꿨는지
- `git pull` 후 작업하고, 테스트가 전부 통과한 뒤에만 커밋·푸시

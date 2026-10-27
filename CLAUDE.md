# myrobo — 작업 안내 (Claude Code용)

본인 한 명이 자기 돈으로 쓰는 개인용 로보어드바이저. 코어-위성 자산배분, 미국 시장만.
증권사 API가 없어서 1단계는 반자동: 시스템이 주문안을 계산하고, 사람이 삼성증권 앱에서 직접 주문한다.

- 사용자: 신입 백엔드 개발자(Java/Spring). 설명과 커밋 메시지는 **한국어**. Windows + PowerShell
- 우선순위: **1) 주문 안전성 2) 수익률 3) 법적 문제 없음**. 애매하면 더 안전한 쪽으로
- 설계 문서: README의 아키텍처·알고리즘 명세 링크

## 명령

```powershell
.\gradlew.bat test      # DB·네트워크 없이 도는 테스트 전부 (현재 108개 = 단위 93 + 골든 15)
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
| `ops` | 매일 08:00 작업 `DailyJob`(수집 → 주문안 → 알림), 텔레그램 알림 `Notifier`, 배치 기록 job_run, 수동 실행 | 있음 |

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

## 자동 생성·알림 (구현됨, 앱 기동·텔레그램 확인 전)

- `DailyJob`이 08:00 KST에 수집 → (수집 오류 없을 때만) `PlanService.create()` → 알림. 예약은 여기 하나뿐(`CollectorJob`은 수집 한 번만). 주문은 여전히 사람이 한다
- 수집에 오류가 있으면 그날 주문안을 만들지 않고 알림. 경고만 있으면 계속. `robo.daily.auto-plan=false`면 수집만
- 알림: SENT, REJECTED, 수집·단계 오류, 미완료 주문안 리마인드, 데이터 검증에 걸린 "주문 없음". 조용한 경우: 정상 "주문 없음", 오늘 이미 있음. 총자산 같은 합계는 외부로 보내지 않는다
- 환경변수 `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` 둘 다 있어야 켜짐. 토큰은 URL에 들어가므로 로그·예외에 URL을 찍지 않는다
- 수동: `POST /internal/jobs/daily`(전체 실행, 알림도 실제 전송), `POST /internal/jobs/notify-test`(테스트 알림). 둘 다 `X-Robo-Request: 1` 필요

## 완료 신호·따라잡기 (구현됨, 실제 DB·healthchecks.io 확인 전)

- 완료 신호: `DailyJob.run()` 끝에 `HEALTHCHECK_URL`(healthchecks.io 핑 URL, 비밀 키 포함이라 환경변수로만)로 POST. 정상이면 URL, 사람이 볼 일(수집 오류·중단, 주문안 폐기·오류, 알림 전송 실패)이면 `URL/fail`에 `collection=… plan=…` 같은 짧은 코드만 실어 보낸다. 정상 "주문 없음"·오늘 이미 있음·수집만(auto-plan=false)은 성공. URL은 로그에 찍지 않는다
- 앱이 죽거나 PC가 꺼져 신호가 끊기면 감시 서비스(하루 1번, 유예 2시간 정도로 설정)가 알려 준다. `HEALTHCHECK_URL`이 없으면 꺼짐
- 알림 전송 실패: 알림이 켜져 있는데 보내지 못하면 `job_run`에 `notify-daily` FAILED를 남기고, 다음에 보내는 첫 알림 앞에 "지난번 알림이 전송에 실패했어요"를 붙인다(전달되면 SUCCESS로 덮어 멈춤). 알림이 꺼진 상태는 실패로 치지 않는다
- 따라잡기: 기동 60초 뒤 오늘(KST) 08:10 이후인데 오늘 끝난 `plan-daily` 기록이 없으면 `run()`을 한 번 돌린다(`robo.daily.catch-up`, `catch-up-after`, `catch-up-delay-seconds`). 수집·auto-plan이 꺼져 있으면 안 한다. 수집이 예외로 중단된 날은 기록이 없어 다음 기동 때 다시 시도된다
- 사용자 확인: 앱 기동 로그에 "완료 신호 켜짐", `POST /internal/jobs/daily` 뒤 healthchecks.io 화면에 핑이 찍히는지, 같은 날 다시 기동해도 따라잡기가 또 돌지 않는지(`tools/`의 SQL로 `job_run` 확인)

그다음: 주간 백업, 일별 평가액과 낙폭 경보.

## 2단계: 자동 주문 (한국투자증권, 계획 · 아직 시작 안 함)

목표: 사용자가 돈을 넣으면 알아서 굴려 준다 (본인 계좌 자동매매. 남의 돈을 맡는 서비스가 아니라 투자일임 규제 대상이 아님).
삼성증권은 개인용 주문 API가 없어서, 주문 API가 있는 **한국투자증권(KIS Developers)** 계좌로 옮긴다.

**사용자가 할 일** (계좌 개설 전이면 먼저):
1. 한국투자증권 계좌 개설 (비대면) + **해외주식 거래 신청·환전 설정** (계좌만으로는 미국 주식 거래가 안 되는 경우가 많음)
2. **상시 모의투자 신청**
3. KIS Developers 서비스 신청 → 앱키·앱시크릿 발급 (계좌 단위). HTS ID에 `$`·`@`가 있으면 나중에 ID 변경 시 API 키 제한이 생길 수 있으니 신청 전에 확인
4. 앱키·앱시크릿은 채팅·파일·커밋에 넣지 말고 환경변수로만 (실제 주문이 나가는 키)

**확인해야 할 것 (2차 자료로는 확인 못 함, 공식 포털 apiportal.koreainvestment.com·GitHub koreainvestment/open-trading-api에서 직접 확인)**:
- 해외주식 주문이 **모의투자 서버에서도 되는지** (모의 서버 주소가 실전과 다름)
- 해외 호출 한도, **시장가 주문 가능 여부** (불가면 지정가 + 미체결 처리 필요)
- 토큰 발급 주기 (2차 자료: 약 24시간, 너무 자주 받으면 제한될 수 있음)

**설계 방향** (사용자 승인 전, 구현 시작 금지):
- 엔진·안전 검증·주문안 상태 전이·알림은 그대로. 새로 만드는 건 증권사 어댑터(`BrokerGateway`)와 체결·잔고 동기화
- 흐름: SENT → API로 주문 전송 → 체결 조회 → DONE. 지금의 체결 입력은 반자동 모드·수동 보정용으로 남긴다
- "얼마를 넣으면": API로 현금 잔고를 읽어 늘어난 돈을 쌓인 현금으로 보고, 지금의 분할 구축 로직으로 나눠 산다
- 자동이 되면 버그 = 실제 주문이므로 안전장치 필수: 드라이런·모의투자부터, 주문 1건·하루 합계 한도, 비상 정지, 같은 주문 중복 전송 방지(주문 ID), 증권사 실제 잔고와 myrobo 기록을 매번 대조해 어긋나면 멈춤, 소액으로 몇 주 지켜본 뒤 금액 확대
- 순서: 반자동으로 첫 몇 주 돌려 엔진 확인 → 계좌·키 준비 → 설계안 승인 → 모의투자 키로 어댑터 구현(실제 응답을 보고) → 소액 실전
- 실제 응답 없이 어댑터를 먼저 만들지 않는다. 모의투자 키가 나온 뒤에 시작

## 커밋

- 커밋 메시지는 한국어로, 무엇을 왜 바꿨는지
- `git pull` 후 작업하고, 테스트가 전부 통과한 뒤에만 커밋·푸시

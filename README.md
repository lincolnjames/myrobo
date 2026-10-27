# myrobo — 개인용 로보어드바이저

본인 계좌 하나를 정해진 규칙대로 운용하기 위한 1인용 자산배분·리밸런싱 시스템.
매일 아침 배치가 주문안을 계산해 알려 주고, 실제 주문은 사람이 증권사 앱에서 넣는다 (1단계).

- 설계: [아키텍처 문서](https://claude.ai/artifact/Kx5caWMpahGHDDtfksmFhG) · [리밸런싱 알고리즘 명세](https://claude.ai/artifact/NvogkxSjevPTz6ZW3a793g)
- 스택: Java 17, Spring Boot 4.1, Gradle, PostgreSQL(Supabase), Flyway

## 지금 들어 있는 것

| 구성 | 위치 | 상태 |
| --- | --- | --- |
| 리밸런싱 엔진 | `src/main/java/com/myrobo/portfolio` | 골든 케이스 15개 전부 일치 |
| 규칙 설정 파일 | `config/robo-config.yml` | 시작 시 로딩·검증 |
| 설정 로딩·검증·지문 | `src/main/java/com/myrobo/common/config` | 위험한 설정 실수 시 시작 거부 |
| 설정 승인 검사 | `ConfigApprovalGuard` | 승인 안 된 설정 파일이면 시작 거부 |
| 데이터 수집 | `src/main/java/com/myrobo/collector` | 매일 08:00 일봉·휴장일·환율 수집 |
| 배치 기록·수동 실행 | `src/main/java/com/myrobo/ops` | job_run 기록, `POST /internal/jobs/collect` |
| 잔고 입력·비중 화면 | `src/main/java/com/myrobo/holdings`, `src/main/resources/static` | http://localhost:8080 |
| 비중·이탈 계산 | `portfolio/Valuation.java` | 엔진과 같은 5/25·버퍼 규칙 (교차 검증 테스트) |
| 로컬 전용 보호 | `src/main/java/com/myrobo/web` | Host 검사 + 변경 요청 헤더 필수 |
| 안전 검증 | `src/main/java/com/myrobo/guard` | 규칙 11개, 하나라도 어기면 주문안 폐기 |
| 주문안 | `src/main/java/com/myrobo/plan` | 만들기·저장·만료, 큰 주문안은 매주 25%씩 분할 |
| DB 스키마 | `src/main/resources/db/migration` | V1 1단계 테이블, V2 휴장일, V3 잔고 입력 시각·주문안 설명 |
| 기준 구현·케이스 생성 | `tools/rebalance_ref.py`, `tools/gen_cases.py` | 정답 계산기 |
| 데이터 소스 확인 | `tools/check_data_sources.py` | Tiingo·Finnhub·SEC·환율 API 확인 |

나머지 패키지(`signal`, `backtest`)는 각자의 `package-info.java`에 맡을 일만 적혀 있고, 다음 작업에서 채운다.

### 데이터 수집 규칙

- **일봉 (Tiingo)**: 처음엔 2000년부터 전체 이력, 그 뒤엔 새 날짜만. 단, 새로 받은 날에 **분할·배당이 있으면 그 종목 전체 이력을 다시 받는다.** 분할·배당이 생기면 과거 모든 날의 수정주가가 다시 계산되기 때문에, 새 날만 받으면 옛 값과 새 값이 섞여 이동평균이 틀어진다.
- **호출 한도**: Tiingo 무료 한도(시간당 50·하루 1,000)보다 낮게 45·900으로 제한. 시간당 한도면 기다리고, 하루 한도면 남은 종목은 다음 실행으로 넘긴다.
- **거래일**: 미국 뉴욕 시간 기준. Finnhub 휴장일 중 종일 휴장만 휴장으로, 조기 폐장은 거래일로 본다. 일봉은 17:45(ET) 이후에 나온 것으로 보며, 그래서 수집은 겨울에도 그 이후인 08:00(KST)에 돈다.
- **환율**: 수출입은행 매매기준율을 최근 영업일부터 거슬러 찾고, 키가 없거나 실패하면 Frankfurter.
- **실패 격리**: 한 종목이 실패해도 나머지는 계속하고, 실패와 경고는 로그와 `job_run`에 남는다.

## 처음 실행하기

### 1. 테스트 (DB 없이 됨)

JDK 17 이상이 필요하다.

```bash
./gradlew test          # Windows: gradlew.bat test
```

처음에는 Gradle과 의존성을 내려받느라 몇 분 걸린다. 네트워크·DB 없이 도는 테스트 68개다.

- `RebalancePlannerGoldenTest`: 골든 케이스 15개. 주문 줄, 환전, 실행 후 비중, 현금까지 정답과 완전히 같아야 통과
- `RoboConfigFilesTest`: 실제 설정 파일이 통과하는지, 그리고 비중 합계 오류·주식 상한 초과·거래 불가 종목이 시작을 막는지
- `CollectorParsingTest`: Tiingo·Finnhub·수출입은행·Frankfurter 응답 해석, API 키가 URL에 남지 않는지
- `CollectorFlowTest`: 서머타임·휴장일 거래일 계산, 증분·전체 재수집, 호출 한도, 환율 예비 경로, 한 종목 실패 격리
- `PlanSafetyTest`: 안전 검증 규칙 하나하나(목표 시작일, 잔고·시세·환율 신선도, 미완료 주문안, 잔고 재입력, 보유보다 많이 팔기, 돈 없이 사기, 크기 상한, 주식 비중 상한), 큰 주문안을 매주 나눠 내는 결정, 휴장일·서머타임을 반영한 주문 일정
- `HoldingsLogicTest`: 잔고 입력 검증(모든 오류를 한 번에), 비중·목표·이탈 계산, 화면의 이탈 표시가 리밸런싱 엔진 판단과 같은지, 외부 Host·헤더 없는 변경 요청 거부

### 2. Supabase 준비

1. supabase.com에서 프로젝트를 만들고 DB 비밀번호를 정한다 (리전: Seoul 권장)
2. 프로젝트 화면 **Connect** → **Session pooler** 연결 정보를 확인한다. 무료 플랜의 Direct connection은 IPv6 전용이라 대부분의 집 인터넷에서 안 되므로 Session pooler를 쓴다
3. 테이블은 직접 만들지 않는다. 앱이 처음 시작할 때 Flyway가 `robo` 스키마에 만든다

### 3. 앱 실행

`.env.example`의 DB 항목 세 개와 데이터 키를 환경변수로 넣고 실행한다. `KOREAEXIM_KEY`는 없어도 된다 (Frankfurter로 대신함).

```powershell
# Windows PowerShell
$env:DB_URL="jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require"
$env:DB_USER="postgres.<project-ref>"
$env:DB_PASSWORD="..."
$env:TIINGO_KEY="..."
$env:FINNHUB_KEY="..."
.\gradlew.bat bootRun
```

수집은 매일 08:00(KST)에 자동으로 돈다. 바로 돌려 보려면 앱이 켜진 상태에서 다른 PowerShell 창에서:

```powershell
Invoke-RestMethod -Method Post -Headers @{'X-Robo-Request'='1'} http://localhost:8080/internal/jobs/collect | ConvertTo-Json -Depth 5
```

`X-Robo-Request` 헤더가 없는 변경 요청은 403으로 거부된다 (다른 웹사이트가 브라우저를 통해 몰래 보내는 요청을 막는 장치).
처음 실행은 종목마다 2000년 이후 전체 이력을 받아서 1분 정도 걸린다. 결과는 Supabase `robo` 스키마의 `price_daily`, `fx_rate`, `market_holiday`, `job_run`에서 볼 수 있다.

### 4. 잔고 입력

앱이 켜진 상태에서 브라우저로 http://localhost:8080 을 연다. 증권사 앱에 보이는 보유 수량과 원화·달러 현금을 기준일과 함께 넣고 저장하면, 위쪽 표에 현재 비중과 목표(현금 버퍼 1% 반영), 허용 범위 이탈 여부가 나온다.

- 같은 기준일로 다시 저장하면 그날 기록을 통째로 바꾼다. 이전 날짜 기록은 남는다
- 화이트리스트(VTI·SCHR·GLDM·BIL) 밖의 종목은 저장은 되지만 비중 계산에서 빠진다 (시스템 밖 자산)
- 잔고가 7일보다 오래되면 경고가 뜬다. 주문안 단계에서는 이 경우 주문안을 만들지 않는다
- 비중 표는 주문 제안이 아니다. 주문은 아래 주문안 섹션의 **발송** 상태 주문안대로만 넣는다

### 5. 주문안

화면의 **주문안 만들기**를 누르면 그날 주문안을 계산하고 안전 검증을 거친다 (매일 자동 실행은 다음 작업에서 수집 배치에 연결).

| 결과 | 뜻 |
| --- | --- |
| 발송 | 검증 11개를 모두 통과. 표의 순서(매도 → 환전 → 매수)대로 주문하면 된다 |
| 폐기 | 하나라도 걸림. 걸린 이유가 빨간 줄로 나온다. **이 계산대로 주문하지 않는다** |
| 주문 없음 | 허용 범위 안이거나, 큰 주문안을 나눠 내는 날이 아님 (저장하지 않음) |
| 미완료 주문안 있음 | 발송된 주문안이 끝나기 전에는 새로 만들지 않는다 |

- **하루 한 건, 미완료는 계좌당 하나**: 버튼을 여러 번 눌러도 주문안이 겹치지 않는다. 코드의 계좌 잠금과 DB 제약 두 겹으로 막는다
- **유효 기간**: 발송된 주문안은 미국 거래일 기준 3일 동안 유효하고 그 뒤 만료된다. 만료 뒤에는 실제 잔고를 다시 입력해야 다음 주문안이 나온다 (일부만 체결됐을 수 있으므로)
- **큰 주문안은 나눠서**: 필요한 매매가 총자산의 25%를 넘으면 매주 첫 거래일에 25% 이내로 나눠 낸다. 처음 포트폴리오를 만들 때, 큰돈을 넣었을 때, 폭락 뒤 큰 리밸런싱이 모두 해당된다. 기존 자산을 팔아 옮기는 경우 매도와 매수가 둘 다 크기에 들어가서 4주보다 오래 걸린다
- **목표 시작일 전에는 발송하지 않는다**: `config/robo-config.yml`의 `goal.startDate`(2026-11-02) 전에 만들면 폐기로 나온다
- **체결 입력은 다음 작업**: 그때까지 발송된 주문안은 3거래일 뒤 만료로 닫히고, 잔고를 다시 입력하면 다음 주문안을 만들 수 있다

처음 시작하면 로그에 `현재 설정(version 2026.10.1, ...)을 최초 승인으로 기록함`이 나온다.
그 뒤로 `config/robo-config.yml`을 고치고 다시 시작하면 **승인되지 않은 설정이라 시작이 거부되는 게 정상**이다.
규칙 변경 화면을 만들기 전까지 개발 중에는 이렇게 끌 수 있다 (실제 운용 중에는 켜 둘 것).

```bash
./gradlew bootRun --args='--robo.config.require-approval=false'
```

## 규칙과 정답을 바꾸는 순서

리밸런싱 규칙을 바꿀 때는 Java를 먼저 고치지 않는다.

1. `tools/rebalance_ref.py`(기준 구현)를 고친다
2. `cd tools && python3 gen_cases.py` → `src/test/resources/golden_cases.json`이 다시 만들어지고 불변식 검사 결과가 출력된다
3. `./gradlew test`가 실패하는 부분을 Java에서 맞춘다

정답 JSON이나 테스트 기대값을 손으로 고치지 않는다.

## 설계상 지키는 것

- **엔진은 순수 함수**: `portfolio` 패키지는 Spring·DB·외부 API에 의존하지 않는다. 운영 배치와 백테스트가 같은 코드를 쓴다
- **금액은 BigDecimal / numeric**: float·double을 쓰지 않는다
- **미완료 주문안은 계좌당 하나**: 코드뿐 아니라 DB의 부분 유니크 인덱스로도 막는다
- **같은 날 두 번 돌아도 주문안 1건**: `idempotency_key` 유니크 제약
- **비밀값은 환경변수로만**: 설정 파일에는 환경변수 이름만 적는다
- **DB는 공개 API에 노출하지 않음**: Supabase가 공개하는 `public`이 아닌 `robo` 스키마 + 모든 테이블 RLS
- **입력 화면은 외부 비공개**: `127.0.0.1`에만 바인딩. 원격 접속은 VPN으로

## 1단계 운영 기능 (구현됨)

1. `plan`: 체결 입력으로 주문안 완료(DONE) 처리와 잔고 자동 갱신, 텔레그램 알림
2. `ops`: 수집 뒤 주문안 배치 연결, 완료 신호(외부 감시), 놓친 날 따라잡기, 주간 백업·복구
3. `valuation`: 일별 평가액과 낙폭 경보 (입출금 영향을 뺀 지수 기준, `GET /api/valuation`)

자세한 동작과 남은 확인 항목은 `CLAUDE.md`에 있다.

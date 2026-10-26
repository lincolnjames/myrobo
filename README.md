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
| DB 스키마 | `src/main/resources/db/migration` | V1 1단계 테이블, V2 휴장일 |
| 기준 구현·케이스 생성 | `tools/rebalance_ref.py`, `tools/gen_cases.py` | 정답 계산기 |
| 데이터 소스 확인 | `tools/check_data_sources.py` | Tiingo·Finnhub·SEC·환율 API 확인 |

나머지 패키지(`guard`, `plan`, `web`, `signal`, `backtest`)는 각자의 `package-info.java`에 맡을 일만 적혀 있고, 다음 작업에서 채운다.

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

처음에는 Gradle과 의존성을 내려받느라 몇 분 걸린다. 네트워크·DB 없이 도는 테스트 40개다.

- `RebalancePlannerGoldenTest`: 골든 케이스 15개. 주문 줄, 환전, 실행 후 비중, 현금까지 정답과 완전히 같아야 통과
- `RoboConfigFilesTest`: 실제 설정 파일이 통과하는지, 그리고 비중 합계 오류·주식 상한 초과·거래 불가 종목이 시작을 막는지
- `CollectorParsingTest`: Tiingo·Finnhub·수출입은행·Frankfurter 응답 해석, API 키가 URL에 남지 않는지
- `CollectorFlowTest`: 서머타임·휴장일 거래일 계산, 증분·전체 재수집, 호출 한도, 환율 예비 경로, 한 종목 실패 격리

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
Invoke-RestMethod -Method Post http://localhost:8080/internal/jobs/collect | ConvertTo-Json -Depth 5
```

처음 실행은 종목마다 2000년 이후 전체 이력을 받아서 1분 정도 걸린다. 결과는 Supabase `robo` 스키마의 `price_daily`, `fx_rate`, `market_holiday`, `job_run`에서 볼 수 있다.

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

## 다음 작업 (1단계 나머지)

1. `web`: 잔고·현금·체결 입력 화면 (수집 대상에 보유 종목 추가)
2. `guard`: 데이터 신선도, 미완료 주문안, 주문안 크기 상한, 주식 비중 상한 검증
3. `plan`: 주문안 저장·상태 전이, 텔레그램 알림
4. `ops`: 수집 뒤 주문안 배치 연결, 완료 신호(외부 감시), 주간 백업

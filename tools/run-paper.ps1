# 모의투자 시험용 앱 실행 (시험용 Supabase DB + 한국투자증권 모의투자 키, 포트 8081)
#
# 비밀값은 이 파일에 없다. 실행할 때 다른 파일에서 필요한 $env: 줄만 골라 실행한다 (그 파일의 다른 줄은 실행하지 않음):
#   - DB_URL / DB_USER / DB_PASSWORD   ← 복구 시험용 스크립트 (시험용 Supabase 프로젝트)
#   - KIS_*, TIINGO_KEY, FINNHUB_KEY, KOREAEXIM_KEY, TELEGRAM_*  ← 바탕화면 myrobo-run.ps1
# 운영 DB로 뜨는 사고를 막으려고, 두 파일의 DB_URL이 같으면 멈춘다.
#
# 모드:
#   setup   : 증권사 전송·따라잡기 끔. 화면(http://localhost:8081)에서 모의 계좌 잔고를 입력할 때
#   dry-run : 조회(토큰·매수 가능 금액)까지만 하고 보낼 주문을 기록·알림. 주문은 보내지 않음
#   paper   : 모의투자 계좌에 예약주문을 실제로 접수
#
# 실행:  powershell -ExecutionPolicy Bypass -File <이 폴더>\run-paper.ps1 -Mode dry-run

param(
    [ValidateSet('setup', 'dry-run', 'paper')][string]$Mode = 'setup',
    [string]$KeysScript = "$env:USERPROFILE\Desktop\myrobo-run.ps1",
    [string]$DbScript = "$env:USERPROFILE\myrobo-restore-test\run-restore-test.ps1"
)
$ErrorActionPreference = 'Stop'

foreach ($f in $KeysScript, $DbScript) { if (-not (Test-Path $f)) { throw "파일 없음: $f" } }

# 운영 DB_URL은 비교용으로만 읽는다 ($env:에 넣지 않음)
$prodDbUrl = $null
Get-Content $KeysScript -Encoding UTF8 | Where-Object { $_ -match '^\s*\$env:DB_URL\s*=' } |
    ForEach-Object { Invoke-Expression ($_ -replace '^\s*\$env:DB_URL', '$$prodDbUrl') }

Get-Content $DbScript -Encoding UTF8 | Where-Object { $_ -match '^\s*\$env:DB_(URL|USER|PASSWORD)\s*=' } | ForEach-Object { Invoke-Expression $_ }
Get-Content $KeysScript -Encoding UTF8 |
    Where-Object { $_ -match '^\s*\$env:(KIS_[A-Z_]+|TIINGO_KEY|FINNHUB_KEY|KOREAEXIM_KEY|TELEGRAM_BOT_TOKEN|TELEGRAM_CHAT_ID)\s*=' } |
    ForEach-Object { Invoke-Expression $_ }

if (-not $env:DB_URL) { throw "시험용 DB_URL을 읽지 못했어요: $DbScript" }
if ($prodDbUrl -and ($prodDbUrl.Trim() -eq $env:DB_URL.Trim())) { throw '시험용 DB_URL이 운영 DB와 같아요. 멈춥니다.' }
if ($env:KIS_MODE -ne 'paper') { throw "KIS_MODE가 paper가 아니에요 ($env:KIS_MODE). 모의투자 키로만 시험합니다." }

$env:SERVER_PORT            = '8081'                               # 운영 앱(8080)과 겹치지 않게
$env:BACKUP_DIR             = "$env:USERPROFILE\myrobo-restore-test" # 운영 백업 폴더를 건드리지 않게
$env:ROBO_BACKUP_ENABLED    = 'false'
foreach ($v in 'HEALTHCHECK_URL', 'HEALTHCHECK_BACKUP_URL') { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }  # 운영 감시에 섞이지 않게

if ($Mode -eq 'setup') {
    $env:ROBO_BROKER_MODE    = 'off'
    $env:ROBO_DAILY_CATCH_UP = 'false'
} else {
    $env:ROBO_BROKER_MODE    = $Mode
    $env:ROBO_DAILY_CATCH_UP = 'true'
}
Remove-Item Env:ROBO_COLLECTOR_ENABLED -ErrorAction SilentlyContinue   # 복구 시험 때 껐던 값이 남아 있지 않게 (수집 켬)

Write-Host "모드 $Mode · 포트 8081 · 시험용 DB · 증권사 전송 $env:ROBO_BROKER_MODE"
Set-Location (Split-Path $PSScriptRoot -Parent)
.\gradlew.bat bootRun

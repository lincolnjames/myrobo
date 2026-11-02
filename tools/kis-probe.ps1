# 한국투자증권 모의투자 연결 확인 (읽기 전용, 주문하지 않음)
#
# 바탕화면 myrobo-run.ps1에서 $env:KIS_... 줄만 골라 실행해 키를 읽는다 (파일의 다른 줄은 실행하지 않음).
# 키·토큰은 화면에 찍지 않는다. 응답 본문(잔고·시세)만 보여 준다.
#
# 확인하는 것:
#   1. 모의투자 서버에서 접근 토큰 발급 (발급할 때마다 알림톡이 감. 1분에 1회 제한)
#   2. 해외주식 잔고 (NASD·NYSE·AMEX 각각) / 체결기준 현재잔고 (달러 예수금)
#   3. 코어 ETF 현재가와 맞는 거래소 코드 (AMS·NYS·NAS 순서로 시도)
#   4. 매수가능금액 (VTI 1주 기준. 모의 계좌 달러 예수금이 0이라 원화로 살 수 있는지 확인)
#
# 실행:  powershell -ExecutionPolicy Bypass -File C:\Users\User\myrobo\tools\kis-probe.ps1
#        4번만:  ... kis-probe.ps1 -BuyableOnly
# 요청 형식 출처: github.com/koreainvestment/open-trading-api (kis_devlp.yaml, examples_llm/overseas_stock)

param([string]$ConfigScript = "$env:USERPROFILE\Desktop\myrobo-run.ps1", [switch]$BuyableOnly)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ── 키 읽기: $env:KIS_ 대입 줄만 실행 ──
if (-not (Test-Path $ConfigScript)) { throw "설정 파일 없음: $ConfigScript" }
Get-Content $ConfigScript -Encoding UTF8 | Where-Object { $_ -match '^\s*\$env:KIS_[A-Z_]+\s*=' } | ForEach-Object { Invoke-Expression $_ }
foreach ($v in 'KIS_APP_KEY', 'KIS_APP_SECRET', 'KIS_ACCOUNT', 'KIS_MODE') {
    if (-not (Get-Item "Env:$v" -ErrorAction SilentlyContinue)) { throw "환경변수 $v 가 없어요. myrobo-run.ps1에 넣었는지 확인하세요." }
}
if ($env:KIS_MODE -ne 'paper') { throw "이 스크립트는 모의투자(KIS_MODE='paper')에서만 돌려요. 지금 값: $env:KIS_MODE" }
if (-not ($env:KIS_ACCOUNT.Trim() -match '^(\d{8})-?(\d{2})$')) { throw "KIS_ACCOUNT 형식이 '앞8자리-뒤2자리'가 아니에요." }
$Cano = $Matches[1]; $Prdt = $Matches[2]
$Base = 'https://openapivts.koreainvestment.com:29443'   # 모의투자 (실전은 openapi.koreainvestment.com:9443)

function Read-Body($stream) {
    $ms = New-Object IO.MemoryStream; $stream.CopyTo($ms)
    return [Text.Encoding]::UTF8.GetString($ms.ToArray())
}

# 응답 본문을 UTF-8로 직접 읽는다 (Windows PowerShell 5.1이 charset 없는 응답의 한글을 깨뜨리므로)
function Invoke-Kis([string]$Method, [string]$Path, [hashtable]$Headers, [hashtable]$Query, $Body) {
    $uri = $Base + $Path
    if ($Query) { $uri += '?' + (($Query.GetEnumerator() | ForEach-Object { $_.Key + '=' + [uri]::EscapeDataString([string]$_.Value) }) -join '&') }
    $h = @{ 'charset' = 'UTF-8' } + $Headers
    try {
        $args2 = @{ Uri = $uri; Method = $Method; Headers = $h; ContentType = 'application/json; charset=utf-8'; UseBasicParsing = $true }
        if ($Body) { $args2.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress)) }
        $r = Invoke-WebRequest @args2
        $text = Read-Body $r.RawContentStream
        return @{ ok = $true; status = [int]$r.StatusCode; json = ($text | ConvertFrom-Json); trCont = $r.Headers['tr_cont'] }
    } catch [Net.WebException] {
        $res = $_.Exception.Response
        $text = if ($res) { Read-Body $res.GetResponseStream() } else { $_.Exception.Message }
        $json = $null; try { $json = $text | ConvertFrom-Json } catch { }
        return @{ ok = $false; status = if ($res) { [int]$res.StatusCode } else { 0 }; json = $json; text = $text }
    }
}

function Show-Result([string]$Title, $r) {
    Write-Host ''
    Write-Host "── $Title ──"
    if ($r.json) {
        $j = $r.json
        Write-Host ("HTTP {0} · rt_cd={1} msg_cd={2} msg1={3}" -f $r.status, $j.rt_cd, $j.msg_cd, $j.msg1)
    } else {
        Write-Host ("HTTP {0} · {1}" -f $r.status, $r.text)
    }
}

function Show-Outputs($r) {
    foreach ($name in 'output', 'output1', 'output2', 'output3') {
        $o = $r.json.$name
        if ($null -ne $o) { Write-Host "[$name]"; Write-Host ($o | ConvertTo-Json -Depth 5) }
    }
}

# ── 1. 토큰 ──
$tok = Invoke-Kis 'POST' '/oauth2/tokenP' @{} $null @{ grant_type = 'client_credentials'; appkey = $env:KIS_APP_KEY; appsecret = $env:KIS_APP_SECRET }
Write-Host ''
Write-Host '── 1. 접근 토큰 (모의투자) ──'
if (-not $tok.ok -or -not $tok.json.access_token) {
    Write-Host ("실패: HTTP {0} · {1}" -f $tok.status, ($(if ($tok.json) { ($tok.json | ConvertTo-Json -Compress) } else { $tok.text })))
    Write-Host '키가 모의투자 계좌 것인지, 1분 안에 다시 받으려 한 건 아닌지 확인하세요.'
    exit 1
}
Write-Host ("성공 · 만료 {0} · 종류 {1} (토큰 값은 표시하지 않음)" -f $tok.json.access_token_token_expired, $tok.json.token_type)

$auth = @{ authorization = 'Bearer ' + $tok.json.access_token; appkey = $env:KIS_APP_KEY; appsecret = $env:KIS_APP_SECRET; custtype = 'P' }
function Get-Kis([string]$Path, [string]$TrId, [hashtable]$Query) {
    Start-Sleep -Milliseconds 1100   # 모의투자는 호출 한도가 낮다 (초과 시 EGW00201)
    return Invoke-Kis 'GET' $Path ($auth + @{ tr_id = $TrId }) $Query $null
}

if (-not $BuyableOnly) {
# ── 2. 잔고 ──
foreach ($ex in 'NASD', 'NYSE', 'AMEX') {
    $r = Get-Kis '/uapi/overseas-stock/v1/trading/inquire-balance' 'VTTS3012R' @{
        CANO = $Cano; ACNT_PRDT_CD = $Prdt; OVRS_EXCG_CD = $ex; TR_CRCY_CD = 'USD'; CTX_AREA_FK200 = ''; CTX_AREA_NK200 = '' }
    Show-Result "2-$ex. 해외주식 잔고 (VTTS3012R, $ex)" $r
    if ($r.json) { Show-Outputs $r }
}
$r = Get-Kis '/uapi/overseas-stock/v1/trading/inquire-present-balance' 'VTRP6504R' @{
    CANO = $Cano; ACNT_PRDT_CD = $Prdt; WCRC_FRCR_DVSN_CD = '02'; NATN_CD = '840'; TR_MKET_CD = '00'; INQR_DVSN_CD = '00' }
Show-Result '2-현재잔고. 체결기준 현재잔고 (VTRP6504R, 외화·미국)' $r
if ($r.json) { Show-Outputs $r }

# ── 3. 시세와 거래소 코드 ──
Write-Host ''
Write-Host '── 3. 코어 ETF 현재가 (HHDFS00000300) ──'
foreach ($sym in 'VTI', 'SCHR', 'GLDM', 'BIL') {
    $found = $false
    foreach ($excd in 'AMS', 'NYS', 'NAS') {
        $r = Get-Kis '/uapi/overseas-price/v1/quotations/price' 'HHDFS00000300' @{ AUTH = ''; EXCD = $excd; SYMB = $sym }
        $o = if ($r.json) { $r.json.output } else { $null }
        if ($r.ok -and $r.json.rt_cd -eq '0' -and $o -and $o.last -and [double]$o.last -gt 0) {
            Write-Host ("{0}: 거래소 {1} · 현재가 {2} · 응답 필드 {3}" -f $sym, $excd, $o.last, (($o.PSObject.Properties.Name) -join ','))
            $found = $true
            break
        }
    }
    if (-not $found) {
        Write-Host "${sym}: AMS·NYS·NAS 모두 가격 없음. 마지막 응답:"
        if ($r.json) { Write-Host ($r.json | ConvertTo-Json -Depth 4 -Compress) } else { Write-Host $r.text }
    }
}
}

# ── 4. 매수가능금액 ──
# 시세 조회 거래소 코드는 AMS였지만 주문·잔고 계열은 NASD/NYSE/AMEX를 쓴다. ETF가 어느 쪽으로 받아지는지도 같이 본다
foreach ($ex in 'AMEX', 'NYSE', 'NASD') {
    $r = Get-Kis '/uapi/overseas-stock/v1/trading/inquire-psamount' 'VTTS3007R' @{
        CANO = $Cano; ACNT_PRDT_CD = $Prdt; OVRS_EXCG_CD = $ex; OVRS_ORD_UNPR = '371.57'; ITEM_CD = 'VTI' }
    Show-Result "4-$ex. 매수가능금액 (VTTS3007R, VTI 1주 371.57)" $r
    if ($r.json) { Show-Outputs $r }
}

Write-Host ''
Write-Host '끝. 위 내용을 붙여 주세요. 키·토큰은 찍지 않았어요. 혹시 계좌번호가 보이면 가리고 붙이세요.'

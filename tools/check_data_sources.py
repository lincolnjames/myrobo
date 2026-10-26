"""
데이터 소스 확인 스크립트. 표준 라이브러리만 사용하므로 설치할 것 없음.

사용법 (PC 터미널):
    export FINNHUB_KEY=발급받은키          # Windows PowerShell: $env:FINNHUB_KEY="키"
    export TIINGO_KEY=발급받은키           # 선택. 없으면 Tiingo 확인은 건너뜀
    python3 check_data_sources.py

키는 이 파일에 적지 말 것. 결과 표만 채팅에 붙여 주면 된다 (키는 출력되지 않음).
"""
import calendar
import json
import os
import urllib.error
import urllib.request
from datetime import date, timedelta

SYMBOL = "VTI"
today = date.today()
frm, to = today - timedelta(days=30), today
UA = {"User-Agent": "personal-robo-check (contact: set your email here)"}


def get(url, headers=None):
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # 네트워크 오류 등
        return None, str(e)


def summarize(status, body):
    if status != 200:
        return f"FAIL ({status}) {body[:80]!r}"
    try:
        data = json.loads(body)
    except ValueError:
        return f"OK? 비JSON 응답 {body[:60]!r}"
    if isinstance(data, dict) and data.get("error"):
        return f"FAIL {data['error'][:80]}"
    if isinstance(data, dict) and data.get("s") == "no_data":
        return "OK 이지만 데이터 없음"
    n = len(data) if isinstance(data, (list, dict)) else 1
    sample = json.dumps(data[0] if isinstance(data, list) and data else data, ensure_ascii=False)
    return f"OK  항목 {n}개  예: {sample[:90]}"


checks = []
fk = os.environ.get("FINNHUB_KEY")
if fk:
    base = "https://finnhub.io/api/v1"
    t = f"token={fk}"
    unix = lambda d: calendar.timegm(d.timetuple())  # 윈도우에서도 동작
    checks += [
        ("Finnhub 현재가 /quote", f"{base}/quote?symbol={SYMBOL}&{t}"),
        ("Finnhub 일봉 /stock/candle", f"{base}/stock/candle?symbol={SYMBOL}&resolution=D"
                                      f"&from={unix(frm)}&to={unix(to)}&{t}"),
        ("Finnhub 분할 /stock/split", f"{base}/stock/split?symbol=SCHB&from=2024-01-01&to={to}&{t}"),
        ("Finnhub 배당 /stock/dividend", f"{base}/stock/dividend?symbol={SYMBOL}&from={frm}&to={to}&{t}"),
        ("Finnhub 종목 뉴스 /company-news", f"{base}/company-news?symbol=AAPL&from={today - timedelta(days=3)}&to={to}&{t}"),
        ("Finnhub 휴장일 /stock/market-holiday", f"{base}/stock/market-holiday?exchange=US&{t}"),
        ("Finnhub 실적 발표 /calendar/earnings", f"{base}/calendar/earnings?from={frm}&to={to}&{t}"),
    ]
else:
    print("FINNHUB_KEY 환경변수가 없어 Finnhub 확인은 건너뜀")

tk = os.environ.get("TIINGO_KEY")
if tk:
    checks.append(("Tiingo 일봉(수정주가·분할·배당 포함)",
                   f"https://api.tiingo.com/tiingo/daily/{SYMBOL}/prices?startDate={frm}&token={tk}"))
else:
    print("TIINGO_KEY 환경변수가 없어 Tiingo 확인은 건너뜀")

checks.append(("SEC EDGAR 공시 목록 (키 불필요)",
               "https://data.sec.gov/submissions/CIK0000320193.json"))

print(f"\n확인 일시 {today}, 기준 종목 {SYMBOL}\n")
for name, url in checks:
    status, body = get(url)
    print(f"{name:40s} {summarize(status, body)}")

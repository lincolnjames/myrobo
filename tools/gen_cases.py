"""골든 테스트 케이스를 만들고 불변식을 검사한 뒤 golden_cases.json으로 저장한다."""
import copy
import json
from decimal import Decimal as D

import os
from rebalance_ref import plan, DEFAULT_CONFIG

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "golden_cases.json")

# 가상의 종목 코드. 실제 ETF 이름이 아니다.
STK, BND, GLD, TBL = "US_STOCK", "MID_TREASURY", "GOLD", "T_BILL"
WL = [STK, BND, GLD, TBL]
P36 = {STK: 0.35, BND: 0.30, GLD: 0.10, TBL: 0.25}
P12 = {STK: 0.20, BND: 0.20, GLD: 0.05, TBL: 0.55}
POPEN = {STK: 0.35, "SATELLITE": 0.10, BND: 0.30, GLD: 0.10, TBL: 0.15}
PRICES = {STK: 250, BND: 95, GLD: 200, TBL: 100}
FX = 1400

BASE = {
    "asOf": "2026-11-03",
    "isRebalanceDay": False,
    "mode": "NORMAL",
    "satellitePaper": True,
    "targets": P36,
    "whitelist": WL,
    "prices": PRICES,
    "holdings": {STK: 28, BND: 62, GLD: 10, TBL: 50},  # 거의 목표 비중
    "cash": {"USD": 200, "KRW": 0},
    "fxUsdKrw": FX,
}


def case(cid, desc, **changes):
    inp = copy.deepcopy(BASE)
    for k, v in changes.items():
        inp[k] = v
    return {"id": cid, "description": desc, "input": inp}


cases = [
    case("01_balanced_no_action",
         "목표 근처, 정기일 아님 → 아무것도 안 함"),
    case("02_rebalance_day_no_excess_cash",
         "정기일이지만 버퍼를 넘는 현금이 없음 → 아무것도 안 함",
         isRebalanceDay=True),
    case("03_cash_deploy_with_fx",
         "정기일, 원화 280만원 입금 → 매도 없이 부족한 자산부터 매수, 필요한 만큼만 환전",
         isRebalanceDay=True, cash={"USD": 200, "KRW": 2_800_000}),
    case("04_stock_rally_abs_band_breach",
         "주식 급등으로 목표 대비 +5%p 초과 → 현금으로 못 메워 매도 포함 전체 리밸런싱",
         prices={**PRICES, STK: 320}),
    case("05_gold_drop_rel_band_breach",
         "금 하락으로 -2.5%p(목표 10%의 25%) 초과 → 상대 기준 위반, 전체 리밸런싱",
         prices={**PRICES, GLD: 140}),
    case("06_gold_drop_within_rel_band",
         "금 하락했지만 -2.5%p 이내 → 아무것도 안 함 (경계값 확인)",
         prices={**PRICES, GLD: 165}),
    case("07_breach_fixed_by_cash",
         "금 상대 기준 위반이지만 쌓인 달러 현금으로 메울 수 있음 → 매도 없이 매수만",
         prices={**PRICES, GLD: 140}, cash={"USD": 1200, "KRW": 0}),
    case("08_small_order_dropped",
         "정기일, 단기 국채(1주 45달러)에 1주만 살 돈이 배정 → 50달러 미만이라 주문 없음",
         isRebalanceDay=True, prices={**PRICES, TBL: 45},
         holdings={STK: 28, BND: 63, GLD: 10, TBL: 108}, cash={"USD": 270, "KRW": 0}),
    case("09_under_one_share_dropped",
         "주식에 1주(250달러)보다 적은 금액이 배정돼 제외, 남는 돈은 다른 부족 자산에 1주씩",
         isRebalanceDay=True, cash={"USD": 500, "KRW": 0}),
    case("10_non_whitelisted_excluded",
         "화이트리스트 밖 보유 종목은 총자산·비중 계산에서 제외",
         holdings={STK: 28, BND: 62, GLD: 10, TBL: 50, "KR_STOCK_X": 100},
         prices={**PRICES, "KR_STOCK_X": 50}),
    case("11_profile_shift_scaled",
         "36개월 → 12개월 프로필 전환. 필요한 매매가 총자산의 25%를 넘어 비율대로 축소",
         mode="PROFILE_SHIFT", targets=P12),
    case("12_open_profile_satellite_paper",
         "기한 없는 프로필, 위성 모의 운용 중 → 위성 10%를 코어 비중대로 나눠 목표 계산",
         targets=POPEN),
    case("13_initial_build_from_krw",
         "초기 구축: 원화 2,800만원만 있음 → 이번 주에는 총자산의 25%만 매수",
         mode="INITIAL_BUILD", holdings={}, cash={"USD": 0, "KRW": 28_000_000}),
    case("14_missing_price_error",
         "목표 종목의 가격이 없음 → 주문안을 만들지 않고 오류",
         prices={STK: 250, BND: 95, GLD: 200}),
    case("15_held_asset_not_in_profile",
         "화이트리스트 안이지만 현재 프로필 목표가 0인 자산을 보유 → 전량 매도 대상",
         targets={STK: 0.40, BND: 0.35, TBL: 0.25}),
]


def check_invariants(c, out):
    inp = c["input"]
    if out["action"] == "ERROR":
        return []
    problems = []
    held = inp["holdings"]
    for ln in out["lines"]:
        if ln["side"] == "SELL" and ln["qty"] > held.get(ln["symbol"], 0):
            problems.append(f"sell exceeds holding {ln}")
        if D(ln["estAmountUsd"]) < D(DEFAULT_CONFIG["minOrderUsd"]):
            problems.append(f"line below min order {ln}")
    if D(out["cashAfter"]["USD"]) < 0 or D(out["cashAfter"]["KRW"]) < 0:
        problems.append(f"negative cash {out['cashAfter']}")
    scaled = out["reason"] in ("INITIAL_BUILD", "PROFILE_SHIFT")
    if out["action"] in ("FULL", "BUY_ONLY") and not scaled and out["breachesAfter"]:
        problems.append(f"still breaching after plan {out['breachesAfter']}")
    if scaled and D(out["sizePct"]) > D(DEFAULT_CONFIG["maxPlanPct"]) * 100:
        problems.append(f"scaled plan exceeds size cap {out['sizePct']}")
    # 남는 현금(버퍼 초과분)이 가장 싼 주문 가능 1주 이상인데 놀고 있으면 경고 (축소 모드 제외)
    return problems


results = []
for c in cases:
    out = plan(c["input"])
    c["expected"] = out
    probs = check_invariants(c, out)
    results.append((c["id"], out["action"], out.get("reason", out.get("error")), probs))

with open(OUT, "w", encoding="utf-8") as f:
    json.dump({"config": DEFAULT_CONFIG, "cases": cases}, f, ensure_ascii=False, indent=2)

for cid, action, reason, probs in results:
    print(f"{cid:38s} {action:10s} {reason:20s} {'OK' if not probs else probs}")

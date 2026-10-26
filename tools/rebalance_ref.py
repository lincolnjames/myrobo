"""
코어 리밸런싱 기준 구현 (reference implementation).

목적: Java 구현이 맞는지 판단하는 '정답 계산기'. 운영 코드가 아니다.
골든 테스트 케이스(golden_cases.json)의 expected 값은 이 파일로 계산한다.

금액은 모두 Decimal, 통화는 USD 기준. 원화 현금은 환율로 환산한다.
"""
from __future__ import annotations

from decimal import Decimal, ROUND_FLOOR, ROUND_CEILING, ROUND_HALF_UP
from dataclasses import dataclass, field

D = Decimal

DEFAULT_CONFIG = {
    "bandAbs": "0.05",        # 5/25 규칙의 5%p
    "bandRel": "0.25",        # 5/25 규칙의 25%
    "minOrderUsd": "50",      # 이보다 작은 주문 줄은 버림
    "cashBufferPct": "0.01",  # 달러 현금 버퍼 1%
    "maxPlanPct": "0.25",     # 초기 구축·프로필 전환 시 1회 주문안 규모 상한
}

SATELLITE = "SATELLITE"
EPS = D("0.000001")


class PlanError(Exception):
    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def _q(x: Decimal, places: str = "0.01") -> Decimal:
    return x.quantize(D(places), rounding=ROUND_HALF_UP)


def _floor_int(x: Decimal) -> int:
    return int(x.to_integral_value(rounding=ROUND_FLOOR))


def _ceil_int(x: Decimal) -> int:
    return int(x.to_integral_value(rounding=ROUND_CEILING))


@dataclass
class State:
    total: Decimal
    buffer: Decimal
    excess: Decimal
    usd: Decimal
    krw: Decimal
    fx: Decimal
    prices: dict
    held: dict          # symbol -> qty (화이트리스트 안의 것만)
    weights: dict       # symbol -> 프로필 비중 w (버퍼 적용 전, 합계 1)
    tgt_w: dict         # symbol -> w * (1 - 버퍼)
    tgt_val: dict
    val: dict
    band: dict
    excluded: list = field(default_factory=list)


def resolve_targets(targets: dict, satellite_paper: bool) -> dict:
    t = {k: D(str(v)) for k, v in targets.items()}
    s = sum(t.values())
    if abs(s - 1) > D("0.0001"):
        raise PlanError("BAD_PROFILE", f"profile weights sum to {s}, not 1")
    if SATELLITE in t:
        sat = t.pop(SATELLITE)
        if not satellite_paper:
            raise PlanError("SATELLITE_LIVE_NOT_SUPPORTED",
                            "1단계 코어 알고리즘은 위성 모의 운용 상태만 지원")
        # 위성 몫은 코어 비중대로 나눠 담는다
        t = {k: v / (1 - sat) for k, v in t.items()}
    return t


def build_state(inp: dict, cfg: dict) -> State:
    weights = resolve_targets(inp["targets"], inp.get("satellitePaper", True))
    whitelist = set(inp["whitelist"])
    prices = {k: D(str(v)) for k, v in inp["prices"].items()}
    fx = D(str(inp["fxUsdKrw"]))
    usd = D(str(inp["cash"].get("USD", 0)))
    krw = D(str(inp["cash"].get("KRW", 0)))
    b = D(cfg["cashBufferPct"])

    for sym in weights:
        if sym not in whitelist:
            raise PlanError("TARGET_NOT_WHITELISTED", sym)
        if sym not in prices or prices[sym] <= 0:
            raise PlanError("MISSING_PRICE", sym)

    held, excluded = {}, []
    for sym, qty in inp["holdings"].items():
        if qty == 0:
            continue
        if sym not in whitelist:
            excluded.append(sym)  # 시스템 밖 자산: 총자산·비중 계산에서 제외
            continue
        if sym not in prices or prices[sym] <= 0:
            raise PlanError("MISSING_PRICE", sym)
        held[sym] = int(qty)

    symbols = sorted(set(weights) | set(held))
    for sym in symbols:
        weights.setdefault(sym, D(0))  # 보유 중인데 프로필에 없는 자산은 목표 0

    val = {s: held.get(s, 0) * prices[s] for s in symbols}
    total = sum(val.values()) + usd + krw / fx
    if total <= 0:
        raise PlanError("EMPTY_ACCOUNT", "총자산이 0")
    buffer = b * total
    excess = usd + krw / fx - buffer
    tgt_w = {s: weights[s] * (1 - b) for s in symbols}
    tgt_val = {s: tgt_w[s] * total for s in symbols}
    band = {s: min(D(cfg["bandAbs"]), D(cfg["bandRel"]) * weights[s]) for s in symbols}
    return State(total, buffer, excess, usd, krw, fx, prices, held, weights,
                 tgt_w, tgt_val, val, band, sorted(excluded))


def breaches(st: State, val_after: dict) -> list:
    out = []
    for s in sorted(st.weights):
        cur = val_after[s] / st.total
        dev = cur - st.tgt_w[s]
        if abs(dev) > st.band[s] + EPS:
            out.append(s)
    return out


def _round_trades(st: State, trades: dict) -> tuple[dict, dict]:
    """USD 금액 → 주수. 매수는 내림, 매도도 내림(보유 수량 이내)."""
    buys, sells = {}, {}
    for s, t in trades.items():
        p = st.prices[s]
        if t > EPS:
            q = _floor_int(t / p)
            if q > 0:
                buys[s] = q
        elif t < -EPS:
            q = min(st.held.get(s, 0), _floor_int(-t / p))
            if st.weights[s] == 0:
                q = st.held.get(s, 0)  # 목표 0인 자산은 전량 매도
            if q > 0:
                sells[s] = q
    return buys, sells


def _drop_small(st: State, lines: dict, min_order: Decimal) -> dict:
    return {s: q for s, q in lines.items() if q * st.prices[s] >= min_order}


def _amount(st: State, lines: dict) -> Decimal:
    return sum((q * st.prices[s] for s, q in lines.items()), D(0))


def _krw_convertible_usd(st: State) -> Decimal:
    return D(_floor_int(st.krw / st.fx))


def _available(st: State, sells: dict) -> Decimal:
    # 버퍼를 뺀 달러 현금 + 매도 대금 + 환전 가능한 원화(달러 정수 단위)
    return (st.usd - st.buffer) + _amount(st, sells) + _krw_convertible_usd(st)


def _fund(st: State, buys: dict, sells: dict, allow_extra_sells: bool) -> tuple[dict, dict]:
    """매수 총액이 쓸 수 있는 돈을 넘으면: (허용 시) 가장 많이 초과한 자산을 1주씩 더 팔고,
    더 팔 게 없으면 목표에 가장 가까워진 매수 줄에서 1주씩 뺀다."""
    buys, sells = dict(buys), dict(sells)
    guard = 0
    # 매수가 없으면 조달할 것도 없다 (버퍼가 이미 모자란 경우 포함)
    while buys and _amount(st, buys) > _available(st, sells) + EPS:
        guard += 1
        if guard > 10000:
            raise PlanError("FUNDING_LOOP", "자금 조달 계산이 끝나지 않음")
        candidate = None
        if allow_extra_sells:
            best = D(0)
            for s in sorted(st.weights):  # 동점이면 기호 순서가 앞선 쪽
                remaining = st.held.get(s, 0) - sells.get(s, 0)
                if remaining <= 0 or s in buys:
                    continue
                post = (st.val[s] - sells.get(s, 0) * st.prices[s]) / st.total - st.tgt_w[s]
                if post > best + EPS:
                    best, candidate = post, s
        if candidate is not None:
            sells[candidate] = sells.get(candidate, 0) + 1
            continue
        # 매수 줄 줄이기: 매수 후 비중이 목표 대비 가장 높은(=가장 덜 급한) 줄부터
        worst, pick = None, None
        for s, q in sorted(buys.items()):
            post = (st.val[s] + q * st.prices[s]) / st.total - st.tgt_w[s]
            if worst is None or post > worst:
                worst, pick = post, s
        if pick is None:
            raise PlanError("UNFUNDABLE", "매수할 돈이 부족")
        buys[pick] -= 1
        if buys[pick] == 0:
            del buys[pick]
    return buys, sells


def _buy_only_trades(st: State) -> dict:
    deficits = {s: max(D(0), st.tgt_val[s] - st.val[s]) for s in st.weights}
    total_def = sum(deficits.values())
    cash = st.excess
    if cash <= 0:
        return {s: D(0) for s in st.weights}
    if cash >= total_def:
        rest = cash - total_def
        return {s: deficits[s] + rest * st.weights[s] for s in st.weights}
    if total_def == 0:
        return {s: D(0) for s in st.weights}
    return {s: cash * deficits[s] / total_def for s in st.weights}


def _full_trades(st: State) -> dict:
    return {s: st.tgt_val[s] - st.val[s] for s in st.weights}


def _scale(st: State, trades: dict, cfg: dict) -> tuple[dict, Decimal]:
    turnover = sum(abs(t) for t in trades.values())
    cap = D(cfg["maxPlanPct"]) * st.total
    if turnover <= cap:
        return trades, D(1)
    f = cap / turnover
    return {s: t * f for s, t in trades.items()}, f


def _fill_leftover(st: State, buys: dict, sells: dict) -> dict:
    """반올림으로 남은 돈으로, 목표보다 가장 많이 모자란 자산을 1주씩 더 산다.
    매도 중인 자산, 목표 0인 자산, 1주를 사면 허용 범위를 넘는 자산은 제외."""
    buys = dict(buys)
    while True:
        leftover = _available(st, sells) - _amount(st, buys)
        best_gap, pick = D(0), None
        for s in sorted(st.weights):
            if st.weights[s] == 0 or s in sells:
                continue
            p = st.prices[s]
            if p > leftover + EPS:
                continue
            cur = st.val[s] + buys.get(s, 0) * p
            gap = st.tgt_w[s] - cur / st.total          # 목표 대비 모자란 정도
            after_dev = (cur + p) / st.total - st.tgt_w[s]
            if gap > best_gap + EPS and abs(after_dev) <= st.band[s] + EPS:
                best_gap, pick = gap, s
        if pick is None:
            return buys
        buys[pick] = buys.get(pick, 0) + 1


def _finalize(st: State, buys: dict, sells: dict, cfg: dict,
              allow_extra_sells: bool, fill: bool):
    m = D(cfg["minOrderUsd"])
    buys = _drop_small(st, buys, m)
    sells = _drop_small(st, sells, m)
    buys, sells = _fund(st, buys, sells, allow_extra_sells)
    if fill:
        buys = _fill_leftover(st, buys, sells)
    buys = _drop_small(st, buys, m)
    return buys, sells


def _val_after(st: State, buys: dict, sells: dict) -> dict:
    return {s: st.val[s] + buys.get(s, 0) * st.prices[s] - sells.get(s, 0) * st.prices[s]
            for s in st.weights}


def plan(inp: dict, cfg: dict | None = None) -> dict:
    cfg = {**DEFAULT_CONFIG, **(cfg or {})}
    try:
        st = build_state(inp, cfg)
    except PlanError as e:
        return {"action": "ERROR", "error": e.code, "detail": e.detail}

    mode = inp.get("mode", "NORMAL")
    before = breaches(st, st.val)
    scale = D(1)

    if mode in ("INITIAL_BUILD", "PROFILE_SHIFT"):
        trades, scale = _scale(st, _full_trades(st), cfg)
        buys, sells = _round_trades(st, trades)
        # 축소 모드: 상한을 지키도록 매도를 더하지 않고 매수를 줄이며, 남는 돈도 채우지 않음
        buys, sells = _finalize(st, buys, sells, cfg, allow_extra_sells=False, fill=False)
        action, reason = "FULL", mode
    elif before:
        bo_buys, _ = _round_trades(st, _buy_only_trades(st))
        bo_buys, bo_sells = _finalize(st, bo_buys, {}, cfg, allow_extra_sells=False, fill=True)
        if bo_buys and not breaches(st, _val_after(st, bo_buys, bo_sells)):
            buys, sells = bo_buys, bo_sells
            action, reason = "BUY_ONLY", "BAND_FIXED_BY_CASH"
        else:
            buys, sells = _round_trades(st, _full_trades(st))
            buys, sells = _finalize(st, buys, sells, cfg, allow_extra_sells=True, fill=True)
            action, reason = "FULL", "BAND_BREACH"
    elif inp.get("isRebalanceDay") and st.excess >= D(cfg["minOrderUsd"]):
        buys, _ = _round_trades(st, _buy_only_trades(st))
        buys, sells = _finalize(st, buys, {}, cfg, allow_extra_sells=False, fill=True)
        action, reason = "BUY_ONLY", "CASH_DEPLOY"
    else:
        buys, sells = {}, {}
        action, reason = "NO_ACTION", "WITHIN_BAND" if not inp.get("isRebalanceDay") else "NO_EXCESS_CASH"

    if action != "NO_ACTION" and not buys and not sells:
        action, reason = "NO_ACTION", "ROUNDED_TO_NOTHING"

    sell_amt = _amount(st, sells)
    buy_amt = _amount(st, buys)
    need_usd = buy_amt - (st.usd - st.buffer) - sell_amt
    fx_line = None
    if buys and need_usd > EPS:  # 환전은 매수에 필요한 만큼만
        fx_usd = _ceil_int(need_usd)
        fx_line = {"usd": fx_usd, "krwEst": int(_q(D(fx_usd) * st.fx, "1"))}

    # 정렬: 매도 먼저, 같은 방향 안에서는 금액 큰 순, 금액이 같으면 기호 사전순
    order = lambda kv: (-kv[1] * st.prices[kv[0]], kv[0])
    lines = []
    for s, q in sorted(sells.items(), key=order):
        lines.append({"side": "SELL", "symbol": s, "qty": q,
                      "estAmountUsd": str(_q(q * st.prices[s]))})
    for s, q in sorted(buys.items(), key=order):
        lines.append({"side": "BUY", "symbol": s, "qty": q,
                      "estAmountUsd": str(_q(q * st.prices[s]))})

    after = _val_after(st, buys, sells)
    usd_after = st.usd + sell_amt - buy_amt + (D(fx_line["usd"]) if fx_line else 0)
    krw_after = st.krw - (D(fx_line["krwEst"]) if fx_line else 0)
    size = (sell_amt + buy_amt) / st.total

    return {
        "action": action,
        "reason": reason,
        "lines": lines,
        "fx": fx_line,
        "sizePct": str(_q(size * 100, "0.01")),
        "scaleFactor": str(_q(scale, "0.0001")),
        "totalUsd": str(_q(st.total)),
        "breachesBefore": before,
        "breachesAfter": breaches(st, after),
        "weightsAfterPct": {s: str(_q(after[s] / st.total * 100)) for s in sorted(st.weights)},
        "cashAfter": {"USD": str(_q(usd_after)), "KRW": str(_q(krw_after, "1"))},
        "excludedHoldings": st.excluded,
    }

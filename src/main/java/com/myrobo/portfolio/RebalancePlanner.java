package com.myrobo.portfolio;

import com.myrobo.portfolio.PlanTypes.Action;
import com.myrobo.portfolio.PlanTypes.FxLine;
import com.myrobo.portfolio.PlanTypes.Mode;
import com.myrobo.portfolio.PlanTypes.OrderLine;
import com.myrobo.portfolio.PlanTypes.PlanException;
import com.myrobo.portfolio.PlanTypes.PlannerConfig;
import com.myrobo.portfolio.PlanTypes.Side;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 코어 리밸런싱 계산기.
 *
 * <p>규칙은 "코어 리밸런싱 알고리즘 명세" 문서와 1:1로 대응하고, 정답은 tools/rebalance_ref.py가
 * 계산한 golden_cases.json으로 고정되어 있다. 이 클래스를 고칠 때는 기준 구현을 먼저 고치고
 * 골든 케이스를 다시 만든 뒤 맞춘다.
 *
 * <p>순수 함수: 같은 입력이면 항상 같은 결과. 상태, 시간, I/O에 의존하지 않는다.
 */
public final class RebalancePlanner {

    public static final String SATELLITE = "SATELLITE";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal EPS = new BigDecimal("0.000001");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PROFILE_SUM_TOLERANCE = new BigDecimal("0.0001");
    private static final int FUNDING_LOOP_LIMIT = 10_000;

    private final PlannerConfig cfg;

    public RebalancePlanner(PlannerConfig cfg) {
        this.cfg = Objects.requireNonNull(cfg);
    }

    public Plan plan(PlanInput in) {
        try {
            return doPlan(in);
        } catch (PlanException e) {
            return Plan.error(e.code(), e.detail());
        }
    }

    // ───────────────────────────────── 흐름 ─────────────────────────────────

    private Plan doPlan(PlanInput in) {
        State st = State.build(in, cfg);
        List<String> before = st.breaches(st.val);
        BigDecimal scale = BigDecimal.ONE;

        Action action;
        String reason;
        TreeMap<String, Long> buys;
        TreeMap<String, Long> sells;

        if (in.mode() == Mode.INITIAL_BUILD || in.mode() == Mode.PROFILE_SHIFT) {
            Scaled scaled = st.scale(st.fullTrades());
            scale = scaled.factor();
            Lines r = st.roundTrades(scaled.trades());
            // 축소 모드: 상한을 지키도록 매도를 더하지 않고 매수를 줄이며, 남는 돈도 채우지 않는다
            Lines f = st.finish(r.buys(), r.sells(), false, false);
            buys = f.buys();
            sells = f.sells();
            action = Action.FULL;
            reason = in.mode().name();
        } else if (!before.isEmpty()) {
            Lines bo = st.roundTrades(st.buyOnlyTrades());
            Lines boFinal = st.finish(bo.buys(), new TreeMap<>(), false, true);
            if (!boFinal.buys().isEmpty()
                    && st.breaches(st.valAfter(boFinal.buys(), boFinal.sells())).isEmpty()) {
                buys = boFinal.buys();
                sells = boFinal.sells();
                action = Action.BUY_ONLY;
                reason = "BAND_FIXED_BY_CASH";
            } else {
                Lines full = st.roundTrades(st.fullTrades());
                Lines f = st.finish(full.buys(), full.sells(), true, true);
                buys = f.buys();
                sells = f.sells();
                action = Action.FULL;
                reason = "BAND_BREACH";
            }
        } else if (in.rebalanceDay() && st.excess.compareTo(cfg.minOrderUsd()) >= 0) {
            Lines bo = st.roundTrades(st.buyOnlyTrades());
            Lines f = st.finish(bo.buys(), new TreeMap<>(), false, true);
            buys = f.buys();
            sells = f.sells();
            action = Action.BUY_ONLY;
            reason = "CASH_DEPLOY";
        } else {
            buys = new TreeMap<>();
            sells = new TreeMap<>();
            action = Action.NO_ACTION;
            reason = in.rebalanceDay() ? "NO_EXCESS_CASH" : "WITHIN_BAND";
        }

        if (action != Action.NO_ACTION && buys.isEmpty() && sells.isEmpty()) {
            action = Action.NO_ACTION;
            reason = "ROUNDED_TO_NOTHING";
        }
        return st.toPlan(action, reason, buys, sells, scale, before);
    }

    // ───────────────────────────────── 내부 타입 ─────────────────────────────────

    private record Lines(TreeMap<String, Long> buys, TreeMap<String, Long> sells) {
    }

    private record Scaled(TreeMap<String, BigDecimal> trades, BigDecimal factor) {
    }

    /** 한 번의 계산에 필요한 값. 만들어진 뒤에는 바뀌지 않는다. */
    private static final class State {
        final PlannerConfig cfg;
        final BigDecimal total;
        final BigDecimal buffer;
        final BigDecimal excess;
        final BigDecimal usd;
        final BigDecimal krw;
        final BigDecimal fx;
        final Map<String, BigDecimal> prices;
        final TreeMap<String, Long> held = new TreeMap<>();
        final TreeMap<String, BigDecimal> weights;           // 프로필 비중 (버퍼 적용 전, 합계 1)
        final TreeMap<String, BigDecimal> tgtW = new TreeMap<>();  // weight × (1 − 버퍼)
        final TreeMap<String, BigDecimal> tgtVal = new TreeMap<>();
        final TreeMap<String, BigDecimal> val = new TreeMap<>();
        final TreeMap<String, BigDecimal> band = new TreeMap<>();
        final List<String> excluded;

        private State(PlannerConfig cfg, PlanInput in, TreeMap<String, BigDecimal> weights,
                      List<String> excluded, TreeMap<String, Long> held) {
            this.cfg = cfg;
            this.prices = in.prices();
            this.fx = in.fxUsdKrw();
            this.usd = in.cashUsd();
            this.krw = in.cashKrw();
            this.weights = weights;
            this.excluded = excluded;
            this.held.putAll(held);

            BigDecimal b = cfg.cashBufferPct();
            BigDecimal holdingsValue = BigDecimal.ZERO;
            for (String s : weights.keySet()) {
                BigDecimal v = price(s).multiply(BigDecimal.valueOf(this.held.getOrDefault(s, 0L)), MC);
                val.put(s, v);
                holdingsValue = holdingsValue.add(v, MC);
            }
            BigDecimal krwInUsd = krw.divide(fx, MC);
            this.total = holdingsValue.add(usd, MC).add(krwInUsd, MC);
            if (total.signum() <= 0) {
                throw new PlanException("EMPTY_ACCOUNT", "총자산이 0");
            }
            this.buffer = b.multiply(total, MC);
            this.excess = usd.add(krwInUsd, MC).subtract(buffer, MC);
            BigDecimal keep = BigDecimal.ONE.subtract(b, MC);
            for (Map.Entry<String, BigDecimal> e : weights.entrySet()) {
                String s = e.getKey();
                BigDecimal w = e.getValue();
                BigDecimal tw = w.multiply(keep, MC);
                tgtW.put(s, tw);
                tgtVal.put(s, tw.multiply(total, MC));
                band.put(s, cfg.bandAbs().min(cfg.bandRel().multiply(w, MC)));
            }
        }

        static State build(PlanInput in, PlannerConfig cfg) {
            TreeMap<String, BigDecimal> weights = resolveTargets(in.targets(), in.satellitePaper());
            for (String s : weights.keySet()) {
                if (!in.whitelist().contains(s)) {
                    throw new PlanException("TARGET_NOT_WHITELISTED", s);
                }
                requirePrice(in, s);
            }
            TreeMap<String, Long> held = new TreeMap<>();
            TreeSet<String> excluded = new TreeSet<>();
            for (Map.Entry<String, Long> e : new TreeMap<>(in.holdings()).entrySet()) {
                if (e.getValue() == 0) {
                    continue;
                }
                if (!in.whitelist().contains(e.getKey())) {
                    excluded.add(e.getKey()); // 시스템 밖 자산: 총자산·비중 계산에서 제외
                    continue;
                }
                requirePrice(in, e.getKey());
                held.put(e.getKey(), e.getValue());
            }
            for (String s : held.keySet()) {
                weights.putIfAbsent(s, BigDecimal.ZERO); // 보유 중인데 프로필에 없으면 목표 0
            }
            return new State(cfg, in, weights, List.copyOf(excluded), held);
        }

        private static void requirePrice(PlanInput in, String s) {
            BigDecimal p = in.prices().get(s);
            if (p == null || p.signum() <= 0) {
                throw new PlanException("MISSING_PRICE", s);
            }
        }

        private static TreeMap<String, BigDecimal> resolveTargets(Map<String, BigDecimal> targets,
                                                                  boolean satellitePaper) {
            BigDecimal sum = targets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.subtract(BigDecimal.ONE).abs().compareTo(PROFILE_SUM_TOLERANCE) > 0) {
                throw new PlanException("BAD_PROFILE", "profile weights sum to " + sum + ", not 1");
            }
            TreeMap<String, BigDecimal> t = new TreeMap<>(targets);
            BigDecimal sat = t.remove(SATELLITE);
            if (sat != null) {
                if (!satellitePaper) {
                    throw new PlanException("SATELLITE_LIVE_NOT_SUPPORTED",
                            "1단계 코어 알고리즘은 위성 모의 운용 상태만 지원");
                }
                // 위성 몫은 코어 비중대로 나눠 담는다
                BigDecimal rest = BigDecimal.ONE.subtract(sat, MC);
                t.replaceAll((k, v) -> v.divide(rest, MC));
            }
            return t;
        }

        BigDecimal price(String s) {
            return prices.get(s);
        }

        // ── 판정 ──

        List<String> breaches(Map<String, BigDecimal> valAfter) {
            List<String> out = new ArrayList<>();
            for (String s : weights.keySet()) {
                BigDecimal dev = valAfter.get(s).divide(total, MC).subtract(tgtW.get(s), MC);
                if (dev.abs().compareTo(band.get(s).add(EPS)) > 0) {
                    out.add(s);
                }
            }
            return out;
        }

        TreeMap<String, BigDecimal> valAfter(Map<String, Long> buys, Map<String, Long> sells) {
            TreeMap<String, BigDecimal> out = new TreeMap<>();
            for (String s : weights.keySet()) {
                BigDecimal p = price(s);
                BigDecimal v = val.get(s)
                        .add(p.multiply(BigDecimal.valueOf(buys.getOrDefault(s, 0L)), MC), MC)
                        .subtract(p.multiply(BigDecimal.valueOf(sells.getOrDefault(s, 0L)), MC), MC);
                out.put(s, v);
            }
            return out;
        }

        // ── 목표 금액 ──

        TreeMap<String, BigDecimal> fullTrades() {
            TreeMap<String, BigDecimal> out = new TreeMap<>();
            for (String s : weights.keySet()) {
                out.put(s, tgtVal.get(s).subtract(val.get(s), MC));
            }
            return out;
        }

        /** 투입 가능 현금을 부족분 비율로 나눈다. 다 채우고 남으면 프로필 비중대로. */
        TreeMap<String, BigDecimal> buyOnlyTrades() {
            TreeMap<String, BigDecimal> deficits = new TreeMap<>();
            BigDecimal totalDef = BigDecimal.ZERO;
            for (String s : weights.keySet()) {
                BigDecimal d = tgtVal.get(s).subtract(val.get(s), MC).max(BigDecimal.ZERO);
                deficits.put(s, d);
                totalDef = totalDef.add(d, MC);
            }
            TreeMap<String, BigDecimal> out = new TreeMap<>();
            if (excess.signum() <= 0) {
                weights.keySet().forEach(s -> out.put(s, BigDecimal.ZERO));
                return out;
            }
            if (excess.compareTo(totalDef) >= 0) {
                BigDecimal rest = excess.subtract(totalDef, MC);
                for (String s : weights.keySet()) {
                    out.put(s, deficits.get(s).add(rest.multiply(weights.get(s), MC), MC));
                }
                return out;
            }
            if (totalDef.signum() == 0) {
                weights.keySet().forEach(s -> out.put(s, BigDecimal.ZERO));
                return out;
            }
            for (String s : weights.keySet()) {
                out.put(s, excess.multiply(deficits.get(s), MC).divide(totalDef, MC));
            }
            return out;
        }

        Scaled scale(TreeMap<String, BigDecimal> trades) {
            BigDecimal turnover = trades.values().stream().map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, (a, x) -> a.add(x, MC));
            BigDecimal cap = cfg.maxPlanPct().multiply(total, MC);
            if (turnover.compareTo(cap) <= 0) {
                return new Scaled(trades, BigDecimal.ONE);
            }
            BigDecimal f = cap.divide(turnover, MC);
            TreeMap<String, BigDecimal> out = new TreeMap<>();
            trades.forEach((s, t) -> out.put(s, t.multiply(f, MC)));
            return new Scaled(out, f);
        }

        // ── 주수 변환과 마무리 ──

        /** 금액 → 주수. 매수·매도 모두 내림. 매도는 보유 이내, 목표 0인 종목은 전량 매도. */
        Lines roundTrades(Map<String, BigDecimal> trades) {
            TreeMap<String, Long> buys = new TreeMap<>();
            TreeMap<String, Long> sells = new TreeMap<>();
            for (Map.Entry<String, BigDecimal> e : trades.entrySet()) {
                String s = e.getKey();
                BigDecimal t = e.getValue();
                BigDecimal p = price(s);
                if (t.compareTo(EPS) > 0) {
                    long q = floor(t.divide(p, MC));
                    if (q > 0) {
                        buys.put(s, q);
                    }
                } else if (t.compareTo(EPS.negate()) < 0) {
                    long heldQty = held.getOrDefault(s, 0L);
                    long q = Math.min(heldQty, floor(t.negate().divide(p, MC)));
                    if (weights.get(s).signum() == 0) {
                        q = heldQty;
                    }
                    if (q > 0) {
                        sells.put(s, q);
                    }
                }
            }
            return new Lines(buys, sells);
        }

        Lines finish(TreeMap<String, Long> buys, TreeMap<String, Long> sells,
                     boolean allowExtraSells, boolean fill) {
            TreeMap<String, Long> b = dropSmall(buys);
            TreeMap<String, Long> s = dropSmall(sells);
            Lines funded = fund(b, s, allowExtraSells);
            b = funded.buys();
            s = funded.sells();
            if (fill) {
                b = fillLeftover(b, s);
            }
            return new Lines(dropSmall(b), s);
        }

        TreeMap<String, Long> dropSmall(Map<String, Long> lines) {
            TreeMap<String, Long> out = new TreeMap<>();
            lines.forEach((s, q) -> {
                if (amountOf(s, q).compareTo(cfg.minOrderUsd()) >= 0) {
                    out.put(s, q);
                }
            });
            return out;
        }

        /**
         * 매수 총액이 쓸 수 있는 돈을 넘으면 메운다. 허용 시 목표보다 가장 많이 초과한 종목을 1주씩 더 팔고,
         * 더 팔 게 없으면 매수 후 비중이 목표 대비 가장 높은(가장 덜 급한) 매수 줄에서 1주씩 뺀다.
         */
        Lines fund(TreeMap<String, Long> buysIn, TreeMap<String, Long> sellsIn, boolean allowExtraSells) {
            TreeMap<String, Long> buys = new TreeMap<>(buysIn);
            TreeMap<String, Long> sells = new TreeMap<>(sellsIn);
            int guard = 0;
            while (!buys.isEmpty() && amount(buys).compareTo(available(sells).add(EPS)) > 0) {
                if (++guard > FUNDING_LOOP_LIMIT) {
                    throw new PlanException("FUNDING_LOOP", "자금 조달 계산이 끝나지 않음");
                }
                String candidate = null;
                if (allowExtraSells) {
                    BigDecimal best = BigDecimal.ZERO;
                    for (String s : weights.keySet()) {
                        long remaining = held.getOrDefault(s, 0L) - sells.getOrDefault(s, 0L);
                        if (remaining <= 0 || buys.containsKey(s)) {
                            continue;
                        }
                        BigDecimal post = val.get(s)
                                .subtract(amountOf(s, sells.getOrDefault(s, 0L)), MC)
                                .divide(total, MC).subtract(tgtW.get(s), MC);
                        if (post.compareTo(best.add(EPS)) > 0) {
                            best = post;
                            candidate = s;
                        }
                    }
                }
                if (candidate != null) {
                    sells.merge(candidate, 1L, Long::sum);
                    continue;
                }
                BigDecimal worst = null;
                String pick = null;
                for (Map.Entry<String, Long> e : buys.entrySet()) {
                    String s = e.getKey();
                    BigDecimal post = val.get(s).add(amountOf(s, e.getValue()), MC)
                            .divide(total, MC).subtract(tgtW.get(s), MC);
                    if (worst == null || post.compareTo(worst) > 0) {
                        worst = post;
                        pick = s;
                    }
                }
                if (pick == null) {
                    throw new PlanException("UNFUNDABLE", "매수할 돈이 부족");
                }
                long left = buys.get(pick) - 1;
                if (left == 0) {
                    buys.remove(pick);
                } else {
                    buys.put(pick, left);
                }
            }
            return new Lines(buys, sells);
        }

        /**
         * 반올림으로 남은 돈으로, 목표보다 가장 많이 모자란 종목을 1주씩 더 산다. 매도 중인 종목,
         * 목표 0인 종목, 1주를 사면 허용 범위를 넘는 종목은 제외.
         */
        TreeMap<String, Long> fillLeftover(TreeMap<String, Long> buysIn, TreeMap<String, Long> sells) {
            TreeMap<String, Long> buys = new TreeMap<>(buysIn);
            while (true) {
                BigDecimal leftover = available(sells).subtract(amount(buys), MC);
                BigDecimal bestGap = BigDecimal.ZERO;
                String pick = null;
                for (String s : weights.keySet()) {
                    if (weights.get(s).signum() == 0 || sells.containsKey(s)) {
                        continue;
                    }
                    BigDecimal p = price(s);
                    if (p.compareTo(leftover.add(EPS)) > 0) {
                        continue;
                    }
                    BigDecimal cur = val.get(s).add(amountOf(s, buys.getOrDefault(s, 0L)), MC);
                    BigDecimal gap = tgtW.get(s).subtract(cur.divide(total, MC), MC);
                    BigDecimal afterDev = cur.add(p, MC).divide(total, MC).subtract(tgtW.get(s), MC);
                    if (gap.compareTo(bestGap.add(EPS)) > 0
                            && afterDev.abs().compareTo(band.get(s).add(EPS)) <= 0) {
                        bestGap = gap;
                        pick = s;
                    }
                }
                if (pick == null) {
                    return buys;
                }
                buys.merge(pick, 1L, Long::sum);
            }
        }

        // ── 금액 계산 ──

        BigDecimal amountOf(String s, long q) {
            return price(s).multiply(BigDecimal.valueOf(q), MC);
        }

        BigDecimal amount(Map<String, Long> lines) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, Long> e : lines.entrySet()) {
                sum = sum.add(amountOf(e.getKey(), e.getValue()), MC);
            }
            return sum;
        }

        /** 환전 가능한 원화를 정수 달러로. */
        BigDecimal krwConvertibleUsd() {
            return BigDecimal.valueOf(floor(krw.divide(fx, MC)));
        }

        /** 버퍼를 뺀 달러 현금 + 매도 대금 + 환전 가능한 원화. */
        BigDecimal available(Map<String, Long> sells) {
            return usd.subtract(buffer, MC).add(amount(sells), MC).add(krwConvertibleUsd(), MC);
        }

        // ── 출력 ──

        Plan toPlan(Action action, String reason, TreeMap<String, Long> buys,
                    TreeMap<String, Long> sells, BigDecimal scale, List<String> before) {
            BigDecimal sellAmt = amount(sells);
            BigDecimal buyAmt = amount(buys);
            BigDecimal needUsd = buyAmt.subtract(usd.subtract(buffer, MC), MC).subtract(sellAmt, MC);
            FxLine fxLine = null;
            if (!buys.isEmpty() && needUsd.compareTo(EPS) > 0) { // 환전은 매수에 필요한 만큼만
                long fxUsd = ceil(needUsd);
                long krwEst = BigDecimal.valueOf(fxUsd).multiply(fx, MC)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                fxLine = new FxLine(fxUsd, krwEst);
            }

            List<OrderLine> lines = new ArrayList<>();
            lines.addAll(ordered(Side.SELL, sells));
            lines.addAll(ordered(Side.BUY, buys));

            TreeMap<String, BigDecimal> after = valAfter(buys, sells);
            BigDecimal fxUsd = fxLine == null ? BigDecimal.ZERO : BigDecimal.valueOf(fxLine.usd());
            BigDecimal fxKrw = fxLine == null ? BigDecimal.ZERO : BigDecimal.valueOf(fxLine.krwEst());
            BigDecimal usdAfter = usd.add(sellAmt, MC).subtract(buyAmt, MC).add(fxUsd, MC);
            BigDecimal krwAfter = krw.subtract(fxKrw, MC);
            BigDecimal size = sellAmt.add(buyAmt, MC).divide(total, MC);

            SortedMap<String, BigDecimal> weightsAfter = new TreeMap<>();
            after.forEach((s, v) -> weightsAfter.put(s, pct(v.divide(total, MC))));

            return new Plan(action, reason, null, List.copyOf(lines), fxLine,
                    pct(size), scale.setScale(4, RoundingMode.HALF_UP), money(total),
                    before, breaches(after), weightsAfter,
                    money(usdAfter), krwAfter.setScale(0, RoundingMode.HALF_UP), excluded);
        }

        /** 같은 방향 안에서는 금액 큰 순, 금액이 같으면 기호 사전순. */
        private List<OrderLine> ordered(Side side, Map<String, Long> lines) {
            return lines.entrySet().stream()
                    .map(e -> new OrderLine(side, e.getKey(), e.getValue(), money(amountOf(e.getKey(), e.getValue()))))
                    .sorted(Comparator.comparing(OrderLine::estAmountUsd).reversed()
                            .thenComparing(OrderLine::symbol))
                    .toList();
        }
    }

    private static long floor(BigDecimal x) {
        return x.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private static long ceil(BigDecimal x) {
        return x.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private static BigDecimal money(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio.multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
    }
}

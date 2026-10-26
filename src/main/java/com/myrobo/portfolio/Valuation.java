package com.myrobo.portfolio;

import com.myrobo.portfolio.PlanTypes.PlannerConfig;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 지금 보유 상태의 평가: 총자산, 종목별 현재 비중·목표 비중·차이, 허용 범위(5/25) 이탈 여부.
 *
 * <p>목표와 허용 범위는 {@link RebalancePlanner}와 같은 규칙으로 계산한다
 * (위성 모의 운용이면 위성 몫을 코어 비중대로 나누고, 목표는 현금 버퍼만큼 줄인 값).
 * 그래서 이 화면에서 "이탈"로 보이는 종목은 엔진도 이탈로 본다. 주문은 계산하지 않는다.
 */
public final class Valuation {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal EPS = new BigDecimal("0.000001");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Valuation() {
    }

    public record Position(String symbol, long qty, BigDecimal avgPrice) {
    }

    public record Price(BigDecimal close, LocalDate date) {
    }

    /** 비율 값은 모두 퍼센트(소수 둘째 자리). 평가할 수 없으면 null. */
    public record Row(
            String symbol,
            long qty,
            BigDecimal price,
            LocalDate priceDate,
            BigDecimal valueUsd,
            BigDecimal weightPct,
            BigDecimal targetPct,
            BigDecimal deviationPct,
            BigDecimal bandPct,
            boolean breach) {
    }

    public record View(
            BigDecimal totalUsd,
            BigDecimal totalKrw,
            BigDecimal cashTotalUsd,
            BigDecimal cashWeightPct,
            BigDecimal cashTargetPct,
            List<Row> rows,
            List<Position> excluded,
            List<String> missing,
            boolean anyBreach) {

        /** 가격이나 환율이 빠져 비중을 계산할 수 없는 상태. */
        public boolean complete() {
            return missing.isEmpty();
        }
    }

    public static View of(List<Position> positions, BigDecimal cashUsd, BigDecimal cashKrw,
                          Map<String, Price> prices, BigDecimal fxUsdKrw,
                          Map<String, BigDecimal> targets, boolean satellitePaper,
                          Set<String> whitelist, PlannerConfig cfg) {
        Map<String, BigDecimal> weights = new TreeMap<>(targets);
        BigDecimal sat = weights.remove(RebalancePlanner.SATELLITE);
        if (sat != null && satellitePaper) {
            BigDecimal rest = BigDecimal.ONE.subtract(sat, MC);
            weights.replaceAll((k, v) -> v.divide(rest, MC));
        }

        Map<String, Long> held = new TreeMap<>();
        List<Position> excluded = new ArrayList<>();
        for (Position p : positions) {
            if (p.qty() == 0) {
                continue;
            }
            if (whitelist.contains(p.symbol())) {
                held.merge(p.symbol(), p.qty(), Long::sum);
            } else {
                excluded.add(p); // 시스템 밖 자산: 엔진과 마찬가지로 계산에서 뺀다
            }
        }

        TreeSet<String> symbols = new TreeSet<>(weights.keySet());
        symbols.addAll(held.keySet());

        List<String> missing = new ArrayList<>();
        if (fxUsdKrw == null || fxUsdKrw.signum() <= 0) {
            missing.add("USD/KRW 환율");
        }
        for (String s : symbols) {
            if (prices.get(s) == null) {
                missing.add(s + " 가격");
            }
        }

        BigDecimal cashTotal = fxUsdKrw == null || fxUsdKrw.signum() <= 0 ? null
                : cashUsd.add(cashKrw.divide(fxUsdKrw, MC), MC);
        BigDecimal total = null;
        if (missing.isEmpty()) {
            total = cashTotal;
            for (String s : symbols) {
                total = total.add(prices.get(s).close().multiply(BigDecimal.valueOf(held.getOrDefault(s, 0L)), MC), MC);
            }
        }
        boolean measurable = total != null && total.signum() > 0;

        BigDecimal keep = BigDecimal.ONE.subtract(cfg.cashBufferPct(), MC);
        List<Row> rows = new ArrayList<>();
        boolean anyBreach = false;
        for (String s : symbols) {
            long qty = held.getOrDefault(s, 0L);
            Price price = prices.get(s);
            BigDecimal w = weights.getOrDefault(s, BigDecimal.ZERO);
            BigDecimal tgt = w.multiply(keep, MC);
            BigDecimal band = cfg.bandAbs().min(cfg.bandRel().multiply(w, MC));
            BigDecimal value = price == null ? null : price.close().multiply(BigDecimal.valueOf(qty), MC);

            BigDecimal weight = null;
            BigDecimal dev = null;
            boolean breach = false;
            if (measurable && value != null) {
                weight = value.divide(total, MC);
                dev = weight.subtract(tgt, MC);
                breach = dev.abs().compareTo(band.add(EPS)) > 0;
            }
            anyBreach |= breach;
            rows.add(new Row(s, qty, price == null ? null : price.close(), price == null ? null : price.date(),
                    value == null ? null : money(value), pct(weight), pct(tgt), pct(dev), pct(band), breach));
        }

        return new View(
                total == null ? null : money(total),
                total == null ? null : total.multiply(fxUsdKrw, MC).setScale(0, RoundingMode.HALF_UP),
                cashTotal == null ? null : money(cashTotal),
                measurable ? pct(cashTotal.divide(total, MC)) : null,
                pct(cfg.cashBufferPct()),
                List.copyOf(rows), List.copyOf(excluded), List.copyOf(missing), anyBreach);
    }

    private static BigDecimal money(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
    }
}

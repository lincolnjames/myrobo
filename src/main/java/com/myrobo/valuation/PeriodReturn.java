package com.myrobo.valuation;

import com.myrobo.portfolio.Valuation.Price;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 원화 평가액과, 두 시점 사이의 수익률.
 *
 * <p>수익률은 "앞 시점의 보유 수량·현금을 뒤 시점 가격·환율로 다시 평가한 값 ÷ 앞 시점 평가액 − 1"이다.
 * 그 사이 입출금이나 매매로 구성이 바뀌어도 수익률에는 들어가지 않는다. 분할과 배당은 수집해 둔 기업 행사로
 * 보정한다 (분할일에 가격이 반토막 난 것을 손실로 보지 않도록, 배당락만큼 빠진 가격을 손실로 보지 않도록).
 */
public final class PeriodReturn {

    private static final MathContext MC = MathContext.DECIMAL128;

    private PeriodReturn() {
    }

    /** 한 시점의 보유 상태. 종목 수량과 원화·달러 현금. */
    public record Holdings(Map<String, Long> qty, BigDecimal cashKrw, BigDecimal cashUsd) {

        public static final Holdings EMPTY = new Holdings(Map.of(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** 한 시점의 시장: 종목별 (그 시점까지의) 마지막 종가와 원/달러 환율. */
    public record Market(Map<String, Price> prices, BigDecimal fxUsdKrw) {
    }

    /** 분할(ratio = 새 주식 수 / 옛 주식 수)이나 배당(amount = 1주당 달러). */
    public record Action(String symbol, LocalDate exDate, String type, BigDecimal ratio, BigDecimal amount) {
    }

    /** 가격이나 환율이 없어 평가할 수 없을 때. 무엇이 빠졌는지 담는다. */
    public static final class MissingData extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MissingData(String message) {
            super(message);
        }
    }

    /** 원화 평가액 = Σ 수량 × 종가 × 환율 + 달러 현금 × 환율 + 원화 현금. */
    public static BigDecimal valueKrw(Holdings h, Market m) {
        requireData(h, m);
        BigDecimal usd = h.cashUsd();
        for (var e : h.qty().entrySet()) {
            if (e.getValue() != 0) {
                usd = usd.add(m.prices().get(e.getKey()).close().multiply(BigDecimal.valueOf(e.getValue()), MC), MC);
            }
        }
        if (usd.signum() == 0) {
            return h.cashKrw(); // 달러 자산이 없으면 환율이 없어도 된다
        }
        return usd.multiply(m.fxUsdKrw(), MC).add(h.cashKrw(), MC);
    }

    /**
     * 앞 시점 보유 상태(before)를 기준으로 한 수익률(비율, 0.01 = 1%). 앞 시점 평가액이 0이면 0.
     *
     * @param actions 해당 종목들의 기업 행사. 각 종목의 두 종가 날짜 사이(앞 날짜 제외, 뒤 날짜 포함)만 쓴다
     */
    public static BigDecimal of(Holdings before, Market then, Market now, List<Action> actions) {
        BigDecimal base = valueKrw(before, then);
        if (base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        requireData(before, now);
        BigDecimal usd = before.cashUsd();
        for (var e : before.qty().entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            Price p = then.prices().get(e.getKey());
            Price n = now.prices().get(e.getKey());
            BigDecimal carried = p.close().multiply(relative(e.getKey(), p, n, actions), MC);
            usd = usd.add(carried.multiply(BigDecimal.valueOf(e.getValue()), MC), MC);
        }
        BigDecimal value = usd.multiply(now.fxUsdKrw(), MC).add(before.cashKrw(), MC);
        return value.divide(base, MC).subtract(BigDecimal.ONE, MC);
    }

    /** 한 종목의 총수익 배율: (뒤 종가 × 분할 배율 + 배당) ÷ 앞 종가. 같은 날 종가면 1. */
    static BigDecimal relative(String symbol, Price then, Price now, List<Action> actions) {
        if (!now.date().isAfter(then.date())) {
            return BigDecimal.ONE;
        }
        BigDecimal split = BigDecimal.ONE;
        BigDecimal dividends = BigDecimal.ZERO;
        for (Action a : actions) {
            if (!a.symbol().equals(symbol) || !a.exDate().isAfter(then.date()) || a.exDate().isAfter(now.date())) {
                continue;
            }
            if ("SPLIT".equals(a.type()) && a.ratio() != null && a.ratio().signum() > 0) {
                split = split.multiply(a.ratio(), MC);
            } else if ("DIVIDEND".equals(a.type()) && a.amount() != null) {
                dividends = dividends.add(a.amount(), MC);
            }
        }
        return now.close().multiply(split, MC).add(dividends, MC).divide(then.close(), MC);
    }

    private static void requireData(Holdings h, Market m) {
        TreeSet<String> missing = new TreeSet<>();
        h.qty().forEach((symbol, qty) -> {
            Price p = m.prices().get(symbol);
            if (qty != 0 && (p == null || p.close() == null || p.close().signum() <= 0)) {
                missing.add(symbol);
            }
        });
        boolean needFx = h.cashUsd().signum() != 0 || h.qty().values().stream().anyMatch(q -> q != 0);
        if (needFx && (m.fxUsdKrw() == null || m.fxUsdKrw().signum() <= 0)) {
            missing.add("USD/KRW 환율");
        }
        if (!missing.isEmpty()) {
            throw new MissingData("평가에 필요한 값이 없음: " + String.join(", ", missing));
        }
    }
}

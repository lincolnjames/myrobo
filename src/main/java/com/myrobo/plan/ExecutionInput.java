package com.myrobo.plan;

import com.myrobo.holdings.SnapshotInput;
import com.myrobo.plan.PlanViews.FxCheck;
import com.myrobo.portfolio.Valuation.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 화면에서 받은 체결 결과 (증권사 앱에서 실제로 체결된 그대로).
 * 검증({@link #validate})과 새 잔고 계산({@link #apply})은 DB·Spring 없이 도는 순수 계산이다.
 * 일부만 체결돼도 실제 체결분만 반영하고, 입력이 하나라도 이상하면 전체를 저장하지 않는다.
 *
 * @param fills 주문안 줄마다 하나. 미체결이면 수량 0
 * @param fx    실제 환전. 환전하지 않았으면 null
 */
public record ExecutionInput(List<Fill> fills, Fx fx) {

    private static final int PRICE_SCALE = 6;
    private static final int MONEY_SCALE = 2;

    public record Fill(Long itemId, Long qty, BigDecimal price, BigDecimal fee) {
    }

    /** @param usd 실제로 받은 달러  @param krw 실제로 쓴 원화 */
    public record Fx(BigDecimal usd, BigDecimal krw) {
    }

    /** 저장된 주문안의 한 줄 (검증 기준). */
    public record Item(long id, String side, String symbol, long qty) {
    }

    /** 실제 체결된 줄 (수량 1 이상). */
    public record Executed(long itemId, String side, String symbol, long qty, BigDecimal price, BigDecimal fee) {
    }

    /** 검증을 통과한 결과. fx는 없으면 null. */
    public record Clean(List<Executed> executed, Fx fx, int unfilled) {
    }

    public record Result(Clean clean, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** 체결을 반영한 새 잔고. 계산할 수 없으면 snapshot이 null이고 errors에 이유가 있다. */
    public record Applied(SnapshotInput.Clean snapshot, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Result validate(List<Item> items) {
        List<String> errors = new ArrayList<>();
        Map<Long, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));

        List<Executed> executed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int unfilled = 0;
        List<Fill> given = fills == null ? List.of() : fills;
        for (Fill f : given) {
            Item item = f.itemId() == null ? null : byId.get(f.itemId());
            if (item == null) {
                errors.add("주문안에 없는 줄입니다 (itemId " + f.itemId() + ")");
                continue;
            }
            if (!seen.add(item.id())) {
                errors.add(item.symbol() + " 줄이 두 번 입력됨");
                continue;
            }
            String row = label(item);
            if (f.qty() == null || f.qty() < 0) {
                errors.add(row + ": 체결 수량은 0 이상의 정수여야 함 (미체결이면 0)");
                continue;
            }
            if (f.qty() > item.qty()) {
                errors.add(row + ": 체결 수량 " + f.qty() + "주가 계획 " + item.qty() + "주보다 많음");
                continue;
            }
            BigDecimal fee = f.fee() == null ? BigDecimal.ZERO : f.fee();
            if (fee.signum() < 0 || fee.scale() > PRICE_SCALE) {
                errors.add(row + ": 수수료는 0 이상, 소수 " + PRICE_SCALE + "자리까지");
                continue;
            }
            if (f.qty() == 0) {
                if (fee.signum() != 0) {
                    errors.add(row + ": 미체결(0주)인데 수수료가 입력됨");
                } else {
                    unfilled++;
                }
                continue;
            }
            if (f.price() == null || f.price().signum() <= 0 || f.price().scale() > PRICE_SCALE) {
                errors.add(row + ": 체결 가격은 0보다 크고 소수 " + PRICE_SCALE + "자리까지여야 함");
                continue;
            }
            executed.add(new Executed(item.id(), item.side(), item.symbol(), f.qty(), f.price(), fee));
        }
        for (Item item : items) {
            if (!seen.contains(item.id())) {
                errors.add(label(item) + ": 체결 결과가 입력되지 않음 (미체결이면 0주)");
            }
        }
        Fx cleanFx = checkFx(errors);
        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        return new Result(new Clean(List.copyOf(executed), cleanFx, unfilled), List.of());
    }

    private Fx checkFx(List<String> errors) {
        if (fx == null || (fx.usd() == null && fx.krw() == null)) {
            return null;
        }
        if (fx.usd() == null || fx.krw() == null) {
            errors.add("환전은 받은 달러와 쓴 원화를 둘 다 입력하거나 둘 다 비워야 함");
            return null;
        }
        if (fx.usd().signum() <= 0 || fx.krw().signum() <= 0
                || fx.usd().scale() > MONEY_SCALE || fx.krw().scale() > MONEY_SCALE) {
            errors.add("환전 금액은 0보다 크고 소수 둘째 자리까지여야 함");
            return null;
        }
        return fx;
    }

    private static String label(Item i) {
        return (i.side().equals("SELL") ? "매도 " : "매수 ") + i.symbol();
    }

    /**
     * 직전 잔고에 체결분을 반영한 새 잔고.
     * 수량은 매수만큼 늘고 매도만큼 줄며, 평균 단가는 매수 때만 가중평균으로 바뀐다.
     * 보유보다 많이 팔았거나 현금이 음수가 되면 입력이 틀린 것이므로 저장하지 않는다.
     */
    public static Applied apply(Clean c, List<Position> positions, BigDecimal cashKrw, BigDecimal cashUsd,
                                LocalDate asOf) {
        List<String> errors = new ArrayList<>();
        Map<String, Long> qty = new TreeMap<>();
        Map<String, BigDecimal> avg = new HashMap<>();
        for (Position p : positions) {
            qty.put(p.symbol(), p.qty());
            avg.put(p.symbol(), p.avgPrice());
        }
        BigDecimal usd = cashUsd;
        BigDecimal krw = cashKrw;
        for (Executed e : c.executed()) {
            usd = usd.subtract(e.fee());
        }
        for (Executed e : c.executed()) {
            if (!e.side().equals("SELL")) {
                continue;
            }
            long held = qty.getOrDefault(e.symbol(), 0L);
            if (e.qty() > held) {
                errors.add("매도 " + e.symbol() + " " + e.qty() + "주가 보유 " + held + "주보다 많음");
                continue;
            }
            qty.put(e.symbol(), held - e.qty());
            usd = usd.add(amount(e));
        }
        for (Executed e : c.executed()) {
            if (!e.side().equals("BUY")) {
                continue;
            }
            long held = qty.getOrDefault(e.symbol(), 0L);
            BigDecimal oldAvg = avg.get(e.symbol());
            BigDecimal newAvg;
            if (held == 0) {
                newAvg = e.price();
            } else if (oldAvg == null) {
                newAvg = null; // 직전 평균을 모르면 섞어서 계산하지 않는다
            } else {
                newAvg = oldAvg.multiply(BigDecimal.valueOf(held)).add(amount(e))
                        .divide(BigDecimal.valueOf(held + e.qty()), PRICE_SCALE, RoundingMode.HALF_UP);
            }
            qty.put(e.symbol(), held + e.qty());
            avg.put(e.symbol(), newAvg);
            usd = usd.subtract(amount(e));
        }
        if (c.fx() != null) {
            usd = usd.add(c.fx().usd());
            krw = krw.subtract(c.fx().krw());
        }
        usd = usd.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        krw = krw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (usd.signum() < 0) {
            errors.add("체결을 반영하면 달러 현금이 " + usd.toPlainString() + "달러로 음수가 됨. 가격·수량·환전을 확인");
        }
        if (krw.signum() < 0) {
            errors.add("환전을 반영하면 원화 현금이 " + krw.toPlainString() + "원으로 음수가 됨. 환전 금액을 확인");
        }
        if (!errors.isEmpty()) {
            return new Applied(null, List.copyOf(errors));
        }
        List<SnapshotInput.Line> lines = new ArrayList<>();
        qty.forEach((symbol, q) -> {
            if (q > 0) {
                lines.add(new SnapshotInput.Line(symbol, q, avg.get(symbol)));
            }
        });
        return new Applied(new SnapshotInput.Clean(asOf, List.copyOf(lines), krw, usd), List.of());
    }

    private static BigDecimal amount(Executed e) {
        return e.price().multiply(BigDecimal.valueOf(e.qty()));
    }

    /** 환전 예상(주문안) 대비 실제 환율 차이. 환전을 입력하지 않았으면 null. */
    public static FxCheck fxCheck(Fx actual, Long planFxUsd, Long planFxKrwEst) {
        if (actual == null) {
            return null;
        }
        BigDecimal actualRate = actual.krw().divide(actual.usd(), 4, RoundingMode.HALF_UP);
        if (planFxUsd == null || planFxUsd <= 0 || planFxKrwEst == null) {
            return new FxCheck(null, actualRate, null);
        }
        BigDecimal expected = BigDecimal.valueOf(planFxKrwEst).divide(BigDecimal.valueOf(planFxUsd), 4,
                RoundingMode.HALF_UP);
        BigDecimal diffPct = actualRate.subtract(expected).multiply(BigDecimal.valueOf(100))
                .divide(expected, 2, RoundingMode.HALF_UP);
        return new FxCheck(expected, actualRate, diffPct);
    }

    /**
     * 주문안을 만든 뒤에 직접 입력한 잔고가 있으면 이미 체결이 반영됐을 수 있다.
     * 그 위에 체결을 또 더하면 이중 반영이므로 체결 입력을 막는다.
     */
    public static boolean balanceEnteredAfterPlan(Instant balanceEnteredAt, Instant planCreatedAt) {
        return balanceEnteredAt != null && balanceEnteredAt.isAfter(planCreatedAt);
    }
}

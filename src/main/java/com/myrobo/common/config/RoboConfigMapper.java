package com.myrobo.common.config;

import com.myrobo.common.config.RoboConfig.ChangeKind;
import com.myrobo.common.config.RoboConfig.Goal;
import com.myrobo.common.config.RoboConfig.Rebalance;
import com.myrobo.common.config.RoboConfig.Safety;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * YAML을 읽은 Map을 RoboConfig로 바꾼다. YAML 라이브러리에 의존하지 않으므로 단독으로 테스트할 수 있다.
 * 빠진 항목이나 형식 오류는 경로와 함께 모두 모아서 한 번에 알린다.
 */
public final class RoboConfigMapper {

    private final List<String> errors = new ArrayList<>();

    private RoboConfigMapper() {
    }

    public static RoboConfig fromMap(Map<String, Object> root) {
        RoboConfigMapper m = new RoboConfigMapper();
        RoboConfig cfg = m.map(root == null ? Map.of() : root);
        if (!m.errors.isEmpty()) {
            throw new ConfigException(m.errors);
        }
        return cfg;
    }

    private RoboConfig map(Map<String, Object> root) {
        Map<String, Object> change = obj(root, "change");
        Map<String, Object> goal = obj(root, "goal");
        Map<String, Object> rebalance = obj(root, "rebalance");
        Map<String, Object> band = obj(rebalance, "rebalance.band");
        Map<String, Object> guard = obj(root, "guard");
        Map<String, Object> accounts = obj(root, "accounts");
        Map<String, Object> core = obj(accounts, "accounts.core");
        Map<String, Object> freshness = obj(guard, "guard.dataFreshness");
        Map<String, Object> pending = obj(guard, "guard.pendingPlan");
        Map<String, Object> fx = obj(obj(root, "dataSources"), "dataSources.fx");

        Map<String, Map<String, BigDecimal>> profiles = new LinkedHashMap<>();
        obj(root, "profiles").forEach((horizon, v) -> {
            Map<String, BigDecimal> weights = new TreeMap<>();
            asMap(v, "profiles." + horizon).forEach((k, w) -> weights.put(k, dec(w, "profiles." + horizon + "." + k)));
            profiles.put(horizon, weights);
        });

        Map<String, String> instruments = new TreeMap<>();
        obj(root, "instruments").forEach((assetClass, v) ->
                instruments.put(assetClass, str(asMap(v, "instruments." + assetClass), "symbol",
                        "instruments." + assetClass + ".symbol")));

        Map<String, Set<String>> tradable = new TreeMap<>();
        Map<String, Object> availability = root.get("brokerAvailability") == null
                ? Map.of() : obj(root, "brokerAvailability");
        availability.forEach((broker, v) -> {
            Object list = asMap(v, "brokerAvailability." + broker).get("tradable");
            Set<String> symbols = new TreeSet<>();
            if (list instanceof Collection<?> c) {
                c.forEach(x -> symbols.add(String.valueOf(x)));
            }
            tradable.put(broker, symbols);
        });

        return new RoboConfig(
                str(root, "version", "version"),
                enumOf(ChangeKind.class, str(change, "kind", "change.kind"), "change.kind"),
                str(change, "reason", "change.reason"),
                new Goal(
                        str(goal, "horizon", "goal.horizon"),
                        date(goal.get("startDate"), "goal.startDate"),
                        dec(goal.get("maxDrawdownPct"), "goal.maxDrawdownPct"),
                        str(goal, "baseCurrency", "goal.baseCurrency")),
                profiles,
                instruments,
                str(core, "broker", "accounts.core.broker"),
                tradable,
                new Rebalance(
                        dec(band.get("absPct"), "rebalance.band.absPct"),
                        dec(band.get("relPct"), "rebalance.band.relPct"),
                        dec(rebalance.get("cashBufferPct"), "rebalance.cashBufferPct"),
                        dec(rebalance.get("minOrderUsd"), "rebalance.minOrderUsd"),
                        dec(rebalance.get("maxPlanPct"), "rebalance.maxPlanPct"),
                        integer(rebalance.get("initialBuildWeeks"), "rebalance.initialBuildWeeks")),
                dec(guard.get("equityCapPct"), "guard.equityCapPct"),
                new Safety(
                        integer(freshness.get("priceTradingDays"), "guard.dataFreshness.priceTradingDays"),
                        integer(freshness.get("holdingsDays"), "guard.dataFreshness.holdingsDays"),
                        bool(pending.get("blockNewPlan"), "guard.pendingPlan.blockNewPlan"),
                        integer(pending.get("expireTradingDays"), "guard.pendingPlan.expireTradingDays"),
                        integer(fx.get("maxAgeBusinessDays"), "dataSources.fx.maxAgeBusinessDays")),
                Collections.unmodifiableMap(new LinkedHashMap<>(root))); // YAML의 null 값 허용
    }

    // ── 변환 도우미: 실패해도 바로 던지지 않고 오류를 모은 뒤 기본값을 돌려준다 ──

    private Map<String, Object> obj(Map<String, Object> parent, String path) {
        String key = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return asMap(parent.get(key), path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object v, String path) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, x) -> out.put(String.valueOf(k), x));
            return out;
        }
        errors.add(path + ": 섹션이 없거나 형식이 틀림");
        return Map.of();
    }

    private String str(Map<String, Object> m, String key, String path) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            errors.add(path + ": 값이 없음");
            return "";
        }
        return String.valueOf(v);
    }

    private BigDecimal dec(Object v, String path) {
        if (v instanceof Number || v instanceof String) {
            try {
                return new BigDecimal(v.toString());
            } catch (NumberFormatException e) {
                // 아래에서 오류 기록
            }
        }
        errors.add(path + ": 숫자가 아님 (" + v + ")");
        return BigDecimal.ZERO;
    }

    private int integer(Object v, String path) {
        BigDecimal d = dec(v, path);
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            errors.add(path + ": 정수가 아님 (" + v + ")");
            return 0;
        }
    }

    private boolean bool(Object v, String path) {
        if (v instanceof Boolean b) {
            return b;
        }
        errors.add(path + ": true/false가 아님 (" + v + ")");
        return false;
    }

    private LocalDate date(Object v, String path) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Date d) { // SnakeYAML은 날짜를 UTC 자정의 Date로 읽는다
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (v instanceof String s) {
            try {
                return LocalDate.parse(s);
            } catch (RuntimeException e) {
                // 아래에서 오류 기록
            }
        }
        errors.add(path + ": 날짜(YYYY-MM-DD)가 아님 (" + v + ")");
        return LocalDate.EPOCH;
    }

    private <E extends Enum<E>> E enumOf(Class<E> type, String v, String path) {
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            if (!v.isEmpty()) {
                errors.add(path + ": 허용되지 않는 값 (" + v + ")");
            }
            return type.getEnumConstants()[0];
        }
    }
}

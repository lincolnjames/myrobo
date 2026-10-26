package com.myrobo.portfolio;

import com.myrobo.common.json.Json;
import com.myrobo.portfolio.PlanTypes.Mode;
import com.myrobo.portfolio.PlanTypes.OrderLine;
import com.myrobo.portfolio.PlanTypes.PlannerConfig;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * golden_cases.json을 읽어 입력으로 바꾸고, 엔진 결과와 정답의 차이를 사람이 읽을 수 있는 문장 목록으로 돌려준다.
 * 차이 목록이 비어 있으면 통과.
 */
@SuppressWarnings("unchecked")
final class GoldenCases {

    private final Map<String, Object> root;

    private GoldenCases(Map<String, Object> root) {
        this.root = root;
    }

    static GoldenCases load(InputStream in) throws IOException {
        Objects.requireNonNull(in, "golden_cases.json not found on classpath");
        String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new GoldenCases((Map<String, Object>) Json.parse(json));
    }

    List<Map<String, Object>> cases() {
        return (List<Map<String, Object>>) root.get("cases");
    }

    PlannerConfig config() {
        Map<String, Object> c = (Map<String, Object>) root.get("config");
        return new PlannerConfig(
                dec(c.get("bandAbs")), dec(c.get("bandRel")), dec(c.get("minOrderUsd")),
                dec(c.get("cashBufferPct")), dec(c.get("maxPlanPct")));
    }

    static PlanInput toInput(Map<String, Object> in) {
        Map<String, Object> cash = (Map<String, Object>) in.get("cash");
        Map<String, Long> holdings = new TreeMap<>();
        ((Map<String, Object>) in.get("holdings")).forEach((k, v) -> holdings.put(k, dec(v).longValueExact()));
        return new PlanInput(
                LocalDate.parse((String) in.get("asOf")),
                Boolean.TRUE.equals(in.get("isRebalanceDay")),
                Mode.valueOf((String) in.getOrDefault("mode", "NORMAL")),
                !Boolean.FALSE.equals(in.get("satellitePaper")),
                decMap(in.get("targets")),
                new HashSet<>((List<String>) in.get("whitelist")),
                decMap(in.get("prices")),
                holdings,
                dec(cash.getOrDefault("USD", BigDecimal.ZERO)),
                dec(cash.getOrDefault("KRW", BigDecimal.ZERO)),
                dec(in.get("fxUsdKrw")));
    }

    static List<String> diff(Plan a, Map<String, Object> e) {
        List<String> d = new ArrayList<>();
        check(d, "action", a.action().name(), e.get("action"));
        if ("ERROR".equals(e.get("action"))) {
            check(d, "error", a.reason(), e.get("error"));
            check(d, "detail", a.detail(), e.get("detail"));
            return d;
        }
        check(d, "reason", a.reason(), e.get("reason"));

        List<Map<String, Object>> el = (List<Map<String, Object>>) e.get("lines");
        List<String> actualLines = a.lines().stream().map(GoldenCases::line).toList();
        List<String> expectedLines = el.stream()
                .map(l -> l.get("side") + " " + l.get("symbol") + " " + dec(l.get("qty")).toPlainString()
                        + " " + l.get("estAmountUsd"))
                .toList();
        check(d, "lines", actualLines, expectedLines);

        Map<String, Object> efx = (Map<String, Object>) e.get("fx");
        String actualFx = a.fx() == null ? "none" : a.fx().usd() + " USD / " + a.fx().krwEst() + " KRW";
        String expectedFx = efx == null ? "none"
                : dec(efx.get("usd")).toPlainString() + " USD / " + dec(efx.get("krwEst")).toPlainString() + " KRW";
        check(d, "fx", actualFx, expectedFx);

        check(d, "sizePct", str(a.sizePct()), e.get("sizePct"));
        check(d, "scaleFactor", str(a.scaleFactor()), e.get("scaleFactor"));
        check(d, "totalUsd", str(a.totalUsd()), e.get("totalUsd"));
        check(d, "breachesBefore", a.breachesBefore(), e.get("breachesBefore"));
        check(d, "breachesAfter", a.breachesAfter(), e.get("breachesAfter"));

        Map<String, String> actualW = new TreeMap<>();
        a.weightsAfterPct().forEach((k, v) -> actualW.put(k, str(v)));
        check(d, "weightsAfterPct", actualW, new TreeMap<>((Map<String, Object>) e.get("weightsAfterPct")));

        Map<String, Object> cash = (Map<String, Object>) e.get("cashAfter");
        check(d, "cashAfter.USD", str(a.cashAfterUsd()), cash.get("USD"));
        check(d, "cashAfter.KRW", str(a.cashAfterKrw()), cash.get("KRW"));
        check(d, "excludedHoldings", a.excludedHoldings(), e.get("excludedHoldings"));
        return d;
    }

    private static String line(OrderLine l) {
        return l.side() + " " + l.symbol() + " " + l.qty() + " " + str(l.estAmountUsd());
    }

    private static void check(List<String> d, String field, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            d.add(field + ": expected " + expected + " but was " + actual);
        }
    }

    private static String str(BigDecimal x) {
        return x == null ? null : x.toPlainString();
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static Map<String, BigDecimal> decMap(Object o) {
        Map<String, BigDecimal> m = new TreeMap<>();
        ((Map<String, Object>) o).forEach((k, v) -> m.put(k, dec(v)));
        return m;
    }
}

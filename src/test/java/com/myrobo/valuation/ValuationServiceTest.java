package com.myrobo.valuation;

import com.myrobo.common.config.LoadedConfig;
import com.myrobo.common.config.RoboConfigFiles;
import com.myrobo.holdings.HoldingsRepository;
import com.myrobo.holdings.HoldingsRepository.Cash;
import com.myrobo.portfolio.Valuation.Position;
import com.myrobo.portfolio.Valuation.Price;
import com.myrobo.valuation.Drawdown.Row;
import com.myrobo.valuation.PeriodReturn.Action;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 평가 실행 흐름: 어느 날짜의 잔고·가격을 읽고, 무엇을 저장하고, 언제 알리는지. DB는 가짜. */
class ValuationServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate TUE = MON.plusDays(1);
    private static LoadedConfig config;

    @BeforeAll
    static void loadConfig() throws IOException {
        config = RoboConfigFiles.load(Path.of("config/robo-config.yml")); // 경보선 15/20, 한도 25
    }

    /** 날짜별 잔고(기준일 → 수량·현금), 날짜별 종가, 날짜별 환율, 저장된 기록을 가진 가짜 DB. */
    private static final class Db {
        Long account = 1L;
        final TreeMap<LocalDate, Map<String, Long>> snapshots = new TreeMap<>();
        final TreeMap<LocalDate, Cash> cash = new TreeMap<>();
        final Map<String, TreeMap<LocalDate, BigDecimal>> closes = new HashMap<>();
        final TreeMap<LocalDate, BigDecimal> fx = new TreeMap<>();
        final List<Action> actions = new ArrayList<>();
        final TreeMap<LocalDate, Row> rows = new TreeMap<>();

        Db holdings(LocalDate asOf, Map<String, Long> qty, String krw, String usd) {
            snapshots.put(asOf, qty);
            cash.put(asOf, new Cash(new BigDecimal(krw), new BigDecimal(usd)));
            return this;
        }

        Db close(String symbol, LocalDate date, String close) {
            closes.computeIfAbsent(symbol, k -> new TreeMap<>()).put(date, new BigDecimal(close));
            return this;
        }

        Db fx(LocalDate date, String rate) {
            fx.put(date, new BigDecimal(rate));
            return this;
        }

        final HoldingsRepository holdingsRepo = new HoldingsRepository(null) {
            @Override public Optional<Long> coreAccountId() { return Optional.ofNullable(account); }
            @Override public List<Position> positionsAt(long a, LocalDate asOf) {
                List<Position> out = new ArrayList<>();
                snapshots.get(asOf).forEach((s, q) -> out.add(new Position(s, q, null)));
                return out;
            }
            @Override public Cash cashAt(long a, LocalDate asOf) { return cash.get(asOf); }
        };

        final ValuationRepository repo = new ValuationRepository(null) {
            @Override public Optional<LocalDate> asOfOnOrBefore(long a, LocalDate d) {
                return Optional.ofNullable(snapshots.floorKey(d));
            }
            @Override public Map<String, Price> pricesBefore(Collection<String> symbols, LocalDate d) {
                Map<String, Price> out = new HashMap<>();
                for (String s : symbols) {
                    var e = closes.getOrDefault(s, new TreeMap<>()).lowerEntry(d); // 전날까지
                    if (e != null) {
                        out.put(s, new Price(e.getValue(), e.getKey()));
                    }
                }
                return out;
            }
            @Override public Optional<BigDecimal> fxBefore(LocalDate d) {
                return Optional.ofNullable(fx.lowerEntry(d)).map(Map.Entry::getValue);
            }
            @Override public List<Action> actions(Collection<String> symbols, LocalDate from, LocalDate to) {
                return actions;
            }
            @Override public Optional<Row> previous(LocalDate d) {
                return Optional.ofNullable(rows.lowerEntry(d)).map(Map.Entry::getValue);
            }
            @Override public Optional<Integer> levelOn(LocalDate d) {
                return Optional.ofNullable(rows.get(d)).map(Row::level);
            }
            @Override public void upsert(Row r) { rows.put(r.date(), r); }
        };

        ValuationService service() {
            return new ValuationService(repo, holdingsRepo, config);
        }
    }

    /** 일요일 기준 10주 + 달러 100, VTI 종가 금(10/30) 300, 환율 1400. */
    private static Db base() {
        return new Db()
                .holdings(MON.minusDays(10), Map.of("VTI", 10L), "0", "100")
                .close("VTI", LocalDate.of(2026, 10, 30), "300")
                .fx(LocalDate.of(2026, 10, 30), "1400");
    }

    @Test
    void theFirstRunRecordsIndexOneHundred() {
        var db = base();

        var o = db.service().run(MON);

        assertEquals(ValuationService.Status.RECORDED, o.status());
        assertEquals(new BigDecimal("4340000.00"), o.row().totalKrw()); // (10 × 300 + 100) × 1400
        assertEquals(0, Drawdown.START_NAV.compareTo(db.rows.get(MON).nav()));
        assertNull(o.row().returnPct());
        assertTrue(o.alert().isEmpty());
    }

    @Test
    void theNextDayChainsOnlyThePriceMoveNotTheDeposit() {
        var db = base();
        db.service().run(MON);
        // 월요일 밤: VTI 330 (+10%), 그리고 원화 100만 원 입금을 화요일 잔고로 입력
        db.close("VTI", MON, "330").fx(MON, "1400").holdings(TUE, Map.of("VTI", 10L), "1000000", "100");

        var o = db.service().run(TUE);

        // 어제 보유분: 3000+100 달러 → 3300+100 달러, 입금 100만 원은 수익률에 안 들어감
        assertEquals(new BigDecimal("9.677419"), o.row().returnPct());
        assertEquals(new BigDecimal("5760000.00"), o.row().totalKrw()); // 오늘 평가액에는 입금 포함
    }

    @Test
    void aWithdrawalDoesNotLookLikeADrawdown() {
        var db = base();
        db.service().run(MON);
        db.close("VTI", MON, "300").fx(MON, "1400").holdings(TUE, Map.of("VTI", 5L), "0", "100"); // 5주를 팔아 출금

        var o = db.service().run(TUE);

        assertEquals(0, o.row().returnPct().signum());
        assertEquals(0, o.row().drawdownPct().signum());
    }

    @Test
    void crossingTheFirstLineAlertsOnceEvenIfRunTwiceThatDay() {
        var db = base();
        db.service().run(MON);
        db.close("VTI", MON, "250").fx(MON, "1400"); // 3100 → 2600 달러: -16.13%

        var first = db.service().run(TUE);
        var again = db.service().run(TUE);

        assertEquals(1, first.row().level());
        assertEquals("ALERT", first.alert().orElseThrow().level().action());
        assertTrue(again.alert().isEmpty());
        assertEquals(1, db.rows.size() - 1); // 화요일은 한 줄만 (덮어씀)
    }

    @Test
    void missingPricesFailWithoutWritingARow() {
        var db = base().holdings(MON, Map.of("VTI", 10L, "GLDM", 3L), "0", "0");

        var o = db.service().run(MON);

        assertEquals(ValuationService.Status.FAILED, o.status());
        assertTrue(o.detail().contains("GLDM"), o.detail());
        assertTrue(db.rows.isEmpty());
    }

    @Test
    void noHoldingsEnteredYetIsSkipped() {
        var db = new Db();

        var o = db.service().run(MON);

        assertEquals(ValuationService.Status.SKIPPED, o.status());
        assertTrue(db.rows.isEmpty());
    }

    @Test
    void noCoreAccountIsSkipped() {
        var db = base();
        db.account = null;

        assertEquals(ValuationService.Status.SKIPPED, db.service().run(MON).status());
    }
}

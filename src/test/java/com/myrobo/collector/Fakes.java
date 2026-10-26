package com.myrobo.collector;

import com.myrobo.collector.CollectorStores.FxStore;
import com.myrobo.collector.CollectorStores.HolidayStore;
import com.myrobo.collector.CollectorStores.PriceStore;
import com.myrobo.collector.CollectorTypes.DailyBar;
import com.myrobo.collector.CollectorTypes.FxQuote;
import com.myrobo.collector.CollectorTypes.MarketHoliday;
import com.myrobo.collector.http.HttpFetcher;
import com.myrobo.collector.http.HttpFetcher.FetchException;
import com.myrobo.collector.http.Sleeper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** 수집 테스트용 가짜들. 네트워크·DB 없이 수집 흐름 전체를 돌린다. */
final class Fakes {

    private Fakes() {
    }

    /** URL 일부 → 응답. 요청 기록을 남긴다. 매칭이 없으면 404. */
    static final class FakeHttp implements HttpFetcher {
        final Map<String, Function<URI, String>> routes = new LinkedHashMap<>();
        final List<URI> requests = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();

        FakeHttp on(String urlPart, String body) {
            routes.put(urlPart, u -> body);
            return this;
        }

        FakeHttp onFail(String urlPart, int status) {
            routes.put(urlPart, u -> {
                throw new FetchException(status, u.getPath() + " → HTTP " + status);
            });
            return this;
        }

        @Override
        public String get(URI uri, Map<String, String> h) {
            requests.add(uri);
            headers.add(h);
            for (var e : routes.entrySet()) {
                if (uri.toString().contains(e.getKey())) {
                    return e.getValue().apply(uri);
                }
            }
            throw new FetchException(404, uri.getPath() + " → HTTP 404");
        }

        long count(String urlPart) {
            return requests.stream().filter(u -> u.toString().contains(urlPart)).count();
        }
    }

    /** sleep하면 그만큼 시간이 흐르는 시계. */
    static final class MutableClock extends Clock implements Sleeper {
        Instant now;
        final List<Duration> slept = new ArrayList<>();

        MutableClock(Instant start) {
            this.now = start;
        }

        @Override
        public void sleep(Duration d) {
            slept.add(d);
            now = now.plus(d);
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    static final class MemoryPriceStore implements PriceStore {
        final Map<String, TreeMap<LocalDate, DailyBar>> bars = new TreeMap<>();
        final List<DailyBar> actions = new ArrayList<>();

        @Override
        public Optional<LocalDate> lastDate(String symbol) {
            var m = bars.get(symbol);
            return m == null || m.isEmpty() ? Optional.empty() : Optional.of(m.lastKey());
        }

        @Override
        public void saveBars(List<DailyBar> list) {
            list.forEach(b -> bars.computeIfAbsent(b.symbol(), k -> new TreeMap<>()).put(b.date(), b));
        }

        @Override
        public void saveCorporateActions(List<DailyBar> list) {
            actions.addAll(list);
        }
    }

    static final class MemoryHolidayStore implements HolidayStore {
        final Set<LocalDate> closed = new HashSet<>();
        int saves;

        @Override
        public void save(List<MarketHoliday> list) {
            saves++;
            list.stream().filter(MarketHoliday::fullDayClosed).forEach(h -> closed.add(h.date()));
        }

        @Override
        public Set<LocalDate> fullDayClosures() {
            return Set.copyOf(closed);
        }
    }

    static final class MemoryFxStore implements FxStore {
        final List<FxQuote> saved = new ArrayList<>();

        @Override
        public void save(FxQuote q) {
            saved.add(q);
        }
    }

    /** Tiingo 응답 한 줄. */
    static String tiingoBar(String date, String close, String adjClose, String div, String split) {
        return "{\"date\":\"" + date + "T00:00:00.000Z\",\"close\":" + close + ",\"high\":" + close
                + ",\"low\":" + close + ",\"open\":" + close + ",\"volume\":1000,\"adjClose\":" + adjClose
                + ",\"adjHigh\":" + adjClose + ",\"adjLow\":" + adjClose + ",\"adjOpen\":" + adjClose
                + ",\"adjVolume\":1000,\"divCash\":" + div + ",\"splitFactor\":" + split + "}";
    }

    static String array(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }
}

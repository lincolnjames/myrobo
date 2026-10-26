package com.myrobo.collector.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 최근 1시간·1일 호출 횟수를 세는 슬라이딩 윈도우 제한기.
 * 시간당 한도에 닿으면 가장 오래된 호출이 1시간 창을 벗어날 때까지 기다리고,
 * 하루 한도에 닿으면 기다리지 않고 예외를 던진다 (남은 수집은 다음 실행으로 넘긴다).
 *
 * <p>호출 기록은 메모리에만 있으므로 앱을 재시작하면 0부터 센다. 그래서 설정값을
 * 실제 한도보다 낮게 잡는다 (Tiingo 무료: 시간당 50·하루 1,000 → 45·900).
 */
public final class RateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final String name;
    private final int perHour;
    private final int perDay;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Deque<Instant> calls = new ArrayDeque<>();

    public RateLimiter(String name, int perHour, int perDay, Clock clock, Sleeper sleeper) {
        if (perHour <= 0 || perDay <= 0) {
            throw new IllegalArgumentException("한도는 1 이상이어야 함");
        }
        this.name = name;
        this.perHour = perHour;
        this.perDay = perDay;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** 호출해도 되면 바로 돌아오고, 시간당 한도면 기다린 뒤 돌아온다. */
    public synchronized void acquire() {
        final Instant now = clock.instant();
        prune(now);
        if (calls.size() >= perDay) {
            throw new LimitExceededException(name + " 하루 호출 한도(" + perDay + "회)에 도달");
        }
        final Instant hourAgo = now.minus(HOUR);
        long inLastHour = calls.stream().filter(t -> t.isAfter(hourAgo)).count();
        Instant callAt = now;
        if (inLastHour >= perHour) {
            Instant oldestInHour = calls.stream().filter(t -> t.isAfter(hourAgo)).findFirst().orElseThrow();
            sleeper.sleep(Duration.between(now, oldestInHour.plus(HOUR)).plusSeconds(1));
            callAt = clock.instant();
            prune(callAt);
        }
        calls.addLast(callAt);
    }

    /** 최근 1시간 호출 수 (테스트·로그용). */
    public synchronized int callsInLastHour() {
        Instant now = clock.instant();
        return (int) calls.stream().filter(t -> t.isAfter(now.minus(HOUR))).count();
    }

    private void prune(Instant now) {
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minus(DAY))) {
            calls.removeFirst();
        }
    }

    public static final class LimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LimitExceededException(String message) {
            super(message);
        }
    }
}

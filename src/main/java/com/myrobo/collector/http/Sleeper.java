package com.myrobo.collector.http;

import java.time.Duration;

/** 기다리기. 테스트에서는 실제로 기다리지 않고 기록만 하는 가짜를 쓴다. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration d);

    static Sleeper real() {
        return d -> {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("대기 중 중단됨", e);
            }
        };
    }
}

package com.myrobo.ops;

/** 앱이 켜져 있는 동안 {@link Ticker}가 주기적으로 부르는 작업. 다른 모듈이 ops에 의존하지 않고 끼어들 수 있게. */
public interface Periodic {

    String name();

    void tick();
}

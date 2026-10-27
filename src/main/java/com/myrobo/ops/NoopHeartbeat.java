package com.myrobo.ops;

/** HEALTHCHECK_URL이 없을 때. 아무것도 보내지 않는다. */
public final class NoopHeartbeat implements Heartbeat {

    @Override
    public void success() {
    }

    @Override
    public void failure(String reason) {
    }
}

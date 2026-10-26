package com.myrobo.ops;

/** 알림 설정(토큰, chat id)이 없을 때. 아무것도 보내지 않는다. */
public final class NoopNotifier implements Notifier {

    @Override
    public boolean send(String text) {
        return false;
    }

    @Override
    public boolean enabled() {
        return false;
    }
}

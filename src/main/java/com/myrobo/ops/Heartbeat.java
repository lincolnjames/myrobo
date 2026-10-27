package com.myrobo.ops;

/**
 * 외부 감시 서비스에 보내는 "오늘 작업이 끝났다" 신호. 앱이 죽거나 PC가 꺼지면 신호가 끊기고,
 * 감시 서비스가 그걸 알려 준다. 구현체는 예외를 던지지 않는다. 신호 때문에 본 작업이 멈추면 안 된다.
 * 사유에는 비밀값이나 금액을 넣지 않는다 (외부로 나가는 글이다).
 */
public interface Heartbeat {

    /** 정상 종료. */
    void success();

    /** 사람이 봐야 하는 종료. reason은 짧은 코드 문구. */
    void failure(String reason);
}

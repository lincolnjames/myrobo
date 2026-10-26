package com.myrobo.ops;

/**
 * 사람에게 알리는 통로. 알림이 실패해도 배치를 멈추면 안 되므로 구현체는 예외를 던지지 않는다.
 * 알림에는 총자산 같은 금액 합계나 비밀값을 넣지 않는다 (외부 서비스로 나가는 글이다).
 */
public interface Notifier {

    /** @return 보냈으면 true. 꺼져 있거나 실패하면 false */
    boolean send(String text);

    /** 실제로 어딘가로 보내는 구현인지 (꺼져 있으면 false). */
    boolean enabled();
}

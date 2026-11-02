package com.myrobo.broker;

/** KIS 연결·토큰 실패. 메시지에 키·토큰·계좌번호를 넣지 않는다. */
public final class KisException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KisException(String message) {
        super(message);
    }

    public KisException(String message, Throwable cause) {
        super(message, cause);
    }
}

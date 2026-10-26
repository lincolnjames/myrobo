package com.myrobo.collector.http;

import java.net.URI;
import java.util.Map;

/**
 * GET 요청 하나. 테스트에서는 미리 준비한 응답을 돌려주는 가짜로 바꿔 끼운다.
 * API 키는 URL이 아니라 헤더로 보내서 로그나 오류 메시지에 키가 찍히지 않게 한다.
 */
@FunctionalInterface
public interface HttpFetcher {

    /** 2xx면 본문을, 아니면 {@link FetchException}을 던진다. */
    String get(URI uri, Map<String, String> headers);

    /** 외부 API 호출 실패. status는 HTTP 상태 코드, 네트워크 오류면 -1. */
    final class FetchException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public FetchException(int status, String message) {
            super(message);
            this.status = status;
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
            this.status = -1;
        }

        public int status() {
            return status;
        }
    }
}

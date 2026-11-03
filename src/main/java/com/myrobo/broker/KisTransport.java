package com.myrobo.broker;

import java.net.URI;
import java.util.Map;

/**
 * KIS로 가는 HTTP 요청 하나. 테스트에서는 준비한 응답을 돌려주는 가짜로 바꿔 끼운다.
 *
 * <p>KIS는 업무 오류(잔고 부족 등)도 HTTP 500에 JSON 본문으로 돌려주므로, 상태 코드와 상관없이 본문을 돌려준다.
 * 네트워크 오류만 {@link KisException}으로 던진다. 주문이 두 번 나가면 안 되므로 다시 시도하지 않는다.
 */
@FunctionalInterface
public interface KisTransport {

    /** @param headers 응답 헤더 중 쓰는 것만, 이름은 소문자 (조회의 다음 쪽 표시 tr_cont) */
    record Response(int status, String body, Map<String, String> headers) {

        public Response(int status, String body) {
            this(status, body, Map.of());
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    Response send(String method, URI uri, Map<String, String> headers, String body);
}

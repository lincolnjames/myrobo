package com.myrobo.web;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 1인용 로컬 앱을 브라우저 경유 공격에서 지키는 규칙.
 *
 * <p>서버가 127.0.0.1에만 열려 있어도, 내가 방문한 악성 사이트가 내 브라우저를 시켜
 * {@code http://localhost:8080}으로 요청을 보낼 수 있다. 그래서 두 가지를 막는다.
 * <ul>
 *   <li><b>CSRF</b>: 데이터를 바꾸는 요청(GET·HEAD·OPTIONS 외)은 {@code X-Robo-Request: 1} 헤더가 있어야 한다.
 *       다른 사이트의 폼은 헤더를 붙일 수 없고, 다른 출처의 fetch가 헤더를 붙이면 사전 요청(preflight)이
 *       필요한데 이 서버는 CORS를 허용하지 않으므로 실제 요청이 나가지 않는다.</li>
 *   <li><b>DNS rebinding</b>: 공격자 도메인을 127.0.0.1로 바꿔 같은 출처처럼 보이게 하는 수법.
 *       Host 헤더가 localhost·127.0.0.1·[::1]이 아니면 거부한다.</li>
 * </ul>
 */
public final class LocalRequestGuard {

    public static final String HEADER = "X-Robo-Request";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private LocalRequestGuard() {
    }

    /** 거부해야 하면 이유를, 통과면 empty. */
    public static Optional<String> check(String method, String path, String host, String roboHeader) {
        if (host == null || !LOCAL_HOSTS.contains(stripPort(host.trim().toLowerCase(Locale.ROOT)))) {
            return Optional.of("허용되지 않은 Host: " + host);
        }
        boolean protectedPath = path.startsWith("/api/") || path.startsWith("/internal/");
        if (protectedPath && !SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT)) && !"1".equals(roboHeader)) {
            return Optional.of(HEADER + " 헤더가 없는 변경 요청");
        }
        return Optional.empty();
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {               // [::1]:8080
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}

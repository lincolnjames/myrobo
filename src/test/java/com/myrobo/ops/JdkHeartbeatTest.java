package com.myrobo.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 완료 신호가 올바른 경로로 가고, 실패해도 예외를 던지지 않는지. 로컬 가짜 서버로 확인한다. */
class JdkHeartbeatTest {

    @Test
    void successGoesToTheUrlAndFailureToSlashFail() throws Exception {
        List<String> hits = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " [" + body + "]");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            // 끝의 슬래시가 있어도 경로가 겹치지 않아야 한다
            var hb = new JdkHeartbeat("http://127.0.0.1:" + server.getAddress().getPort() + "/ping/abc-key/");

            hb.success();
            hb.failure("collection=ERRORS plan=SKIPPED");

            assertEquals(List.of("POST /ping/abc-key []", "POST /ping/abc-key/fail [collection=ERRORS plan=SKIPPED]"), hits);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableServerDoesNotThrow() {
        var hb = new JdkHeartbeat("http://127.0.0.1:1/ping/secret-key");

        assertDoesNotThrow(hb::success);
        assertDoesNotThrow(() -> hb.failure("x"));
    }
}

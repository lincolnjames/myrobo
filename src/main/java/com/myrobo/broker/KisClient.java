package com.myrobo.broker;

import com.myrobo.broker.OrderPlanner.Side;
import com.myrobo.collector.http.Sleeper;
import com.myrobo.common.json.Json;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국투자증권 Open API 해외주식 요청 형식. 판단은 하지 않고 요청을 만들고 응답을 읽기만 한다.
 *
 * <p>형식은 공식 예제 저장소(github.com/koreainvestment/open-trading-api)의 kis_auth.py·overseas_stock 예제와
 * 모의투자 계좌로 직접 받아 본 응답(2026-11-02, tools/kis-probe.ps1)을 따른다.
 * <ul>
 *   <li>접근 토큰은 하루 유효하고 발급할 때마다 알림톡이 가며 1분에 한 번만 받을 수 있다 → 메모리에 두고 만료 1시간 전까지 다시 쓴다</li>
 *   <li>모의투자는 호출 한도가 낮다 → 호출 사이에 1.1초 쉰다</li>
 *   <li>모의투자 TR ID는 실전 TR ID의 첫 글자를 V로 바꾼 것</li>
 * </ul>
 * 키·토큰·계좌번호는 로그와 예외 메시지에 넣지 않는다.
 */
public final class KisClient {

    public enum Env {
        PAPER("https://openapivts.koreainvestment.com:29443"),
        LIVE("https://openapi.koreainvestment.com:9443");

        final String baseUrl;

        Env(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    static final String TOKEN_PATH = "/oauth2/tokenP";
    static final String BUYABLE_PATH = "/uapi/overseas-stock/v1/trading/inquire-psamount";
    static final String RESERVE_PATH = "/uapi/overseas-stock/v1/trading/order-resv";
    static final String BUYABLE_TR = "TTTS3007R";
    static final String RESERVE_BUY_TR = "TTTT3014U";
    static final String RESERVE_SELL_TR = "TTTT3016U";

    /** 앞 요청의 응답을 받은 뒤 다음 요청까지 쉬는 시간. 보낸 시각부터 재면 응답이 느릴 때 서버에 붙어서 도착한다 (2026-11-02 EGW00201). */
    static final Duration PACE = Duration.ofMillis(1100);
    static final String RATE_LIMITED = "EGW00201";
    static final int RATE_LIMIT_ATTEMPTS = 3;
    static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(2);
    static final Duration TOKEN_MIN_GAP = Duration.ofSeconds(61);
    static final Duration TOKEN_REFRESH_BEFORE = Duration.ofHours(1);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern ACCOUNT = Pattern.compile("^(\\d{8})-?(\\d{2})$");

    /** 응답 한 건. rt_cd가 "0"이면 성공. output은 응답의 output 객체(없으면 빈 맵). */
    public record Reply(int httpStatus, String rtCd, String msgCd, String message, Map<String, Object> output,
                        String raw) {

        public boolean ok() {
            return "0".equals(rtCd);
        }

        /** output의 필드. KIS 문서는 대문자, 실제 응답은 소문자라 둘 다 본다. */
        public String field(String name) {
            Object v = output.get(name.toLowerCase());
            if (v == null) {
                v = output.get(name.toUpperCase());
            }
            return v == null ? null : v.toString();
        }

        public String summary() {
            return (rtCd == null ? "HTTP " + httpStatus : msgCd + " " + message).strip();
        }
    }

    /** 매수 가능 금액 (모의 응답에서 확인한 필드: ord_psbl_frcr_amt, max_ord_psbl_qty). */
    public record Buyable(BigDecimal orderableUsd, long maxQty) {
    }

    private final KisTransport transport;
    private final Env env;
    private final String appKey;
    private final String appSecret;
    private final String cano;
    private final String productCode;
    private final Clock clock;
    private final Sleeper sleeper;

    private String token;
    private Instant tokenExpiresAt;
    private Instant lastTokenRequest;
    private Instant lastCallAt;

    public KisClient(KisTransport transport, Env env, String appKey, String appSecret, String account, Clock clock,
                     Sleeper sleeper) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("KIS_APP_KEY, KIS_APP_SECRET이 비어 있음");
        }
        Matcher m = ACCOUNT.matcher(account == null ? "" : account.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("KIS_ACCOUNT 형식이 '앞 8자리-뒤 2자리'가 아님");
        }
        this.transport = transport;
        this.env = env;
        this.appKey = appKey.strip();
        this.appSecret = appSecret.strip();
        this.cano = m.group(1);
        this.productCode = m.group(2);
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public Env env() {
        return env;
    }

    /** 이 가격으로 이 종목을 살 때 쓸 수 있는 달러. */
    public synchronized Buyable buyable(String exchange, String symbol, BigDecimal price) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("CANO", cano);
        q.put("ACNT_PRDT_CD", productCode);
        q.put("OVRS_EXCG_CD", exchange);
        q.put("OVRS_ORD_UNPR", price.toPlainString());
        q.put("ITEM_CD", symbol);
        Reply r = call("GET", BUYABLE_PATH, trId(BUYABLE_TR), q, null);
        if (!r.ok()) {
            throw new KisException("매수 가능 금액 조회 실패: " + r.summary());
        }
        try {
            return new Buyable(new BigDecimal(r.field("ord_psbl_frcr_amt")), Long.parseLong(r.field("max_ord_psbl_qty")));
        } catch (RuntimeException e) {
            throw new KisException("매수 가능 금액 응답을 읽을 수 없음", e);
        }
    }

    /** 미국 예약주문 접수 (지정가). 실패도 예외가 아니라 Reply로 돌려준다 (응답을 그대로 기록하려고). */
    public synchronized Reply reserve(Side side, String exchange, String symbol, long qty, BigDecimal limitPrice) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", cano);
        body.put("ACNT_PRDT_CD", productCode);
        body.put("PDNO", symbol);
        body.put("OVRS_EXCG_CD", exchange);
        body.put("FT_ORD_QTY", Long.toString(qty));
        body.put("FT_ORD_UNPR3", limitPrice.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        body.put("ORD_SVR_DVSN_CD", "0");
        body.put("ORD_DVSN", "00"); // 지정가. 모의투자는 지정가만 된다
        return call("POST", RESERVE_PATH, trId(side == Side.BUY ? RESERVE_BUY_TR : RESERVE_SELL_TR), null, body);
    }

    /** 모의투자 TR ID는 실전 TR ID의 첫 글자를 V로 바꾼 것. */
    String trId(String real) {
        return env == Env.PAPER ? "V" + real.substring(1) : real;
    }

    // ── 내부 ──

    /**
     * 호출 한도 초과(EGW00201)만 잠깐 쉬고 다시 보낸다. 이 오류는 API 관문에서 요청을 받기 전에 거절한 것이라
     * 주문이어도 다시 보내 중복이 생기지 않는다. 다른 오류는 그대로 돌려준다.
     */
    private Reply call(String method, String path, String trId, Map<String, String> query, Map<String, String> body) {
        String bearer = token();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json; charset=utf-8");
        h.put("authorization", "Bearer " + bearer);
        h.put("appkey", appKey);
        h.put("appsecret", appSecret);
        h.put("tr_id", trId);
        h.put("custtype", "P");
        Reply r = null;
        for (int attempt = 1; attempt <= RATE_LIMIT_ATTEMPTS; attempt++) {
            pace();
            try {
                r = reply(transport.send(method, uri(path, query), h, body == null ? null : json(body)));
            } finally {
                lastCallAt = clock.instant();
            }
            if (!RATE_LIMITED.equals(r.msgCd())) {
                return r;
            }
            if (attempt < RATE_LIMIT_ATTEMPTS) {
                sleeper.sleep(RATE_LIMIT_BACKOFF.multipliedBy(attempt));
            }
        }
        return r;
    }

    private synchronized String token() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt.minus(TOKEN_REFRESH_BEFORE))) {
            return token;
        }
        if (lastTokenRequest != null && now.isBefore(lastTokenRequest.plus(TOKEN_MIN_GAP))) {
            throw new KisException("접근 토큰은 1분에 한 번만 받을 수 있어요. 잠시 뒤 다시 시도합니다");
        }
        lastTokenRequest = now;
        pace();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("appsecret", appSecret);
        KisTransport.Response res;
        try {
            res = transport.send("POST", uri(TOKEN_PATH, null),
                    Map.of("content-type", "application/json; charset=utf-8"), json(body));
        } finally {
            lastCallAt = clock.instant();
        }
        Map<String, Object> m = parseObject(res.body());
        Object t = m.get("access_token");
        if (t == null || t.toString().isBlank()) {
            throw new KisException("접근 토큰 발급 실패: HTTP " + res.status() + " "
                    + str(m.get("error_code")) + " " + str(m.get("error_description")));
        }
        token = t.toString();
        tokenExpiresAt = expiry(m, now);
        return token;
    }

    private static Instant expiry(Map<String, Object> m, Instant now) {
        Object at = m.get("access_token_token_expired");
        if (at != null) {
            try {
                return LocalDateTime.parse(at.toString(), EXPIRY).atZone(SEOUL).toInstant();
            } catch (DateTimeParseException ignored) {
                // 아래 expires_in으로
            }
        }
        Object sec = m.get("expires_in");
        if (sec instanceof BigDecimal b) {
            return now.plusSeconds(b.longValue());
        }
        return now.plus(Duration.ofHours(23));
    }

    /** 앞 응답을 받은 시각(lastCallAt)에서 PACE가 지날 때까지 쉰다. */
    private void pace() {
        if (lastCallAt == null) {
            return;
        }
        Duration wait = Duration.between(clock.instant(), lastCallAt.plus(PACE));
        if (!wait.isNegative() && !wait.isZero()) {
            sleeper.sleep(wait);
        }
    }

    private URI uri(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(env.baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            query.forEach((k, v) -> sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
            sb.setLength(sb.length() - 1);
        }
        return URI.create(sb.toString());
    }

    @SuppressWarnings("unchecked")
    static Reply reply(KisTransport.Response res) {
        Map<String, Object> m = parseObject(res.body());
        Object out = m.get("output");
        Map<String, Object> output = out instanceof Map<?, ?> om ? (Map<String, Object>) om
                : out instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> first ? (Map<String, Object>) first
                : Map.of();
        if (!m.containsKey("rt_cd")) {
            return new Reply(res.status(), null, null, "응답을 읽을 수 없음", output, res.body());
        }
        return new Reply(res.status(), str(m.get("rt_cd")), str(m.get("msg_cd")), str(m.get("msg1")), output, res.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String body) {
        try {
            Object o = Json.parse(body == null ? "" : body);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    /** 문자열 값만 있는 JSON 객체. */
    static String json(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(quote(k)).append(':').append(quote(v)).append(','));
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 1);
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

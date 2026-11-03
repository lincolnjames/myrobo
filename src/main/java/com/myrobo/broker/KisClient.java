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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    static final String CANCEL_PATH = "/uapi/overseas-stock/v1/trading/order-resv-ccnl";
    static final String CANCEL_TR = "TTTT3017U";
    /**
     * 예약 취소가 끝났다고 볼 응답 코드. 2026-11-02 모의 서버는 취소에 성공해도 rt_cd=1을 줬다:
     * 40720000(예약주문 취소 완료), 같은 주문을 다시 취소하면 40830000(정정/취소된 예약주문입니다).
     * 정정은 하지 않으므로 40830000도 "더 이상 살아 있는 예약이 아님"으로 본다. 그 밖의 코드는 실패로 둔다.
     */
    static final Set<String> CANCEL_DONE_CODES = Set.of("40720000", "40830000");
    static final String FILLS_PATH = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    static final String FILLS_TR = "TTTS3035R";
    static final String BALANCE_PATH = "/uapi/overseas-stock/v1/trading/inquire-balance";
    static final String BALANCE_TR = "TTTS3012R";
    /** 조회 결과가 없을 때의 응답 코드 (2026-11-03 모의 잔고 조회: rt_cd=0, "조회할 내역(자료)이 없습니다"). */
    static final String NO_DATA = "70070000";
    static final int MAX_PAGES = 10;

    /** 앞 요청의 응답을 받은 뒤 다음 요청까지 쉬는 시간. 보낸 시각부터 재면 응답이 느릴 때 서버에 붙어서 도착한다 (2026-11-02 EGW00201). */
    static final Duration PACE = Duration.ofMillis(1100);
    static final String RATE_LIMITED = "EGW00201";
    static final int RATE_LIMIT_ATTEMPTS = 3;
    static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(2);
    static final Duration TOKEN_MIN_GAP = Duration.ofSeconds(61);
    static final Duration TOKEN_REFRESH_BEFORE = Duration.ofHours(1);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
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

    /**
     * 주문체결내역 한 줄 (2026-11-03 모의 응답에서 확인한 필드). 예약주문은 장 시작에 새 주문번호로 나가므로
     * orderNo는 예약 접수 때의 번호와 다르다 (예약 267 → 주문 36617). orderDate는 주문이 나간 한국 날짜.
     *
     * @param side        매도·매수가 아닌 구분이면 null
     * @param filledPrice 체결 평균 단가 (체결이 없으면 0)
     */
    public record Fill(String orderDate, String orderTime, String orderNo, String originalOrderNo, Side side,
                       String symbol, long orderQty, BigDecimal orderPrice, long filledQty, BigDecimal filledPrice) {
    }

    /** 해외주식 잔고 한 종목 (ovrs_pdno, ovrs_excg_cd, ovrs_cblc_qty, pchs_avg_pric). */
    public record Position(String symbol, String exchange, long qty, BigDecimal avgPrice) {
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

    /**
     * 미국 예약주문 취소. 공식 예제 order_resv_ccnl.py: 접수일자(RSVN_ORD_RCIT_DT, yyyyMMdd)와
     * 예약주문 접수 응답의 ODNO(OVRS_RSVN_ODNO)가 필요하다.
     */
    public synchronized Reply cancelReservation(String orderNo, String receiptDate) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("CANO", cano);
        body.put("ACNT_PRDT_CD", productCode);
        body.put("RSVN_ORD_RCIT_DT", receiptDate);
        body.put("OVRS_RSVN_ODNO", orderNo);
        return call("POST", CANCEL_PATH, trId(CANCEL_TR), null, body);
    }

    /**
     * 주문체결내역 (한국 날짜 from~to, 주문일 기준). 모의 계좌는 종목·거래소·매수매도·체결구분 필터와 주문번호 조회가
     * 안 되므로("" / "00"만 가능) 전체를 받는다. 다음 쪽이 있으면(응답 헤더 tr_cont M·F) 이어 받는다.
     */
    public synchronized List<Fill> fills(LocalDate from, LocalDate to) {
        String all = env == Env.PAPER ? "" : "%";
        List<Fill> out = new ArrayList<>();
        String fk = "";
        String nk = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("CANO", cano);
            q.put("ACNT_PRDT_CD", productCode);
            q.put("PDNO", all);
            q.put("ORD_STRT_DT", from.format(YMD));
            q.put("ORD_END_DT", to.format(YMD));
            q.put("SLL_BUY_DVSN", "00");
            q.put("CCLD_NCCS_DVSN", "00");
            q.put("OVRS_EXCG_CD", all);
            q.put("SORT_SQN", "DS");
            q.put("ORD_DT", "");
            q.put("ORD_GNO_BRNO", "");
            q.put("ODNO", "");
            q.put("CTX_AREA_FK200", fk);
            q.put("CTX_AREA_NK200", nk);
            Page p = page(FILLS_PATH, trId(FILLS_TR), q, page > 0, "주문체결내역 조회");
            try {
                rows(p.body().get("output")).forEach(r -> out.add(fill(r)));
            } catch (RuntimeException e) {
                throw new KisException("주문체결내역 응답을 읽을 수 없음", e);
            }
            if (!p.more()) {
                return out;
            }
            fk = str(p.body().get("ctx_area_fk200"));
            nk = str(p.body().get("ctx_area_nk200"));
        }
        throw new KisException("주문체결내역이 " + MAX_PAGES + "쪽을 넘음");
    }

    /** 이 거래소 코드(AMEX 등)로 조회한 해외주식 잔고 (output1). */
    public synchronized List<Position> positions(String exchange) {
        List<Position> out = new ArrayList<>();
        String fk = "";
        String nk = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("CANO", cano);
            q.put("ACNT_PRDT_CD", productCode);
            q.put("OVRS_EXCG_CD", exchange);
            q.put("TR_CRCY_CD", "USD");
            q.put("CTX_AREA_FK200", fk);
            q.put("CTX_AREA_NK200", nk);
            Page p = page(BALANCE_PATH, trId(BALANCE_TR), q, page > 0, "해외주식 잔고 조회");
            try {
                rows(p.body().get("output1")).forEach(r -> out.add(new Position(str(r.get("ovrs_pdno")),
                        str(r.get("ovrs_excg_cd")), num(r, "ovrs_cblc_qty").longValueExact(), num(r, "pchs_avg_pric"))));
            } catch (RuntimeException e) {
                throw new KisException("해외주식 잔고 응답을 읽을 수 없음", e);
            }
            if (!p.more()) {
                return out;
            }
            fk = str(p.body().get("ctx_area_fk200"));
            nk = str(p.body().get("ctx_area_nk200"));
        }
        throw new KisException("해외주식 잔고가 " + MAX_PAGES + "쪽을 넘음");
    }

    private static Fill fill(Map<String, Object> r) {
        String side = str(r.get("sll_buy_dvsn_cd"));
        return new Fill(str(r.get("ord_dt")), str(r.get("ord_tmd")), str(r.get("odno")), str(r.get("orgn_odno")),
                "01".equals(side) ? Side.SELL : "02".equals(side) ? Side.BUY : null, str(r.get("pdno")),
                num(r, "ft_ord_qty").longValueExact(), num(r, "ft_ord_unpr3"),
                num(r, "ft_ccld_qty").longValueExact(), num(r, "ft_ccld_unpr3"));
    }

    /** 응답의 숫자 문자열. 빈 값은 0. */
    private static BigDecimal num(Map<String, Object> r, String key) {
        String s = str(r.get(key));
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().filter(x -> x instanceof Map<?, ?>).map(x -> (Map<String, Object>) x).toList();
        }
        return o instanceof Map<?, ?> m && !m.isEmpty() ? List.of((Map<String, Object>) m) : List.of();
    }

    /** 예약 취소 응답이 "취소됨"인지. rt_cd만 보면 모의 서버의 성공 응답을 실패로 읽는다 ({@link #CANCEL_DONE_CODES}). */
    public static boolean cancelled(Reply r) {
        return r.ok() || CANCEL_DONE_CODES.contains(r.msgCd());
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
        return reply(send(method, path, trId, query, body, null));
    }

    /** 조회 한 쪽. more: 응답 헤더 tr_cont가 M·F면 다음 쪽이 있다. */
    private record Page(Map<String, Object> body, boolean more) {
    }

    /** 성공(rt_cd 0)이나 "조회할 내역 없음"만 받고, 그 밖에는 예외 (다음 틱에 다시 조회). */
    private Page page(String path, String trId, Map<String, String> query, boolean next, String what) {
        KisTransport.Response res = send("GET", path, trId, query, null, next ? "N" : null);
        Map<String, Object> m = parseObject(res.body());
        String msgCd = str(m.get("msg_cd"));
        if (!"0".equals(str(m.get("rt_cd"))) && !NO_DATA.equals(msgCd)) {
            throw new KisException(what + " 실패: "
                    + (m.containsKey("rt_cd") ? msgCd + " " + str(m.get("msg1")) : "HTTP " + res.status()));
        }
        String trCont = res.header("tr_cont");
        return new Page(m, "M".equals(trCont) || "F".equals(trCont));
    }

    /** @param trCont 이어 받는 조회면 "N", 아니면 null */
    private KisTransport.Response send(String method, String path, String trId, Map<String, String> query,
                                       Map<String, String> body, String trCont) {
        String bearer = token();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json; charset=utf-8");
        h.put("authorization", "Bearer " + bearer);
        h.put("appkey", appKey);
        h.put("appsecret", appSecret);
        h.put("tr_id", trId);
        h.put("custtype", "P");
        if (trCont != null) {
            h.put("tr_cont", trCont);
        }
        KisTransport.Response res = null;
        for (int attempt = 1; attempt <= RATE_LIMIT_ATTEMPTS; attempt++) {
            pace();
            try {
                res = transport.send(method, uri(path, query), h, body == null ? null : json(body));
            } finally {
                lastCallAt = clock.instant();
            }
            if (!RATE_LIMITED.equals(str(parseObject(res.body()).get("msg_cd")))) {
                return res;
            }
            if (attempt < RATE_LIMIT_ATTEMPTS) {
                sleeper.sleep(RATE_LIMIT_BACKOFF.multipliedBy(attempt));
            }
        }
        return res;
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

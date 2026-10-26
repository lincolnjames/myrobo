/**
 * 화면용 API와 로컬 전용 보호.
 *
 * <p>{@link com.myrobo.web.LocalRequestGuard}: Host가 localhost가 아니면 거부(DNS 리바인딩 방어), 변경 요청은
 * {@code X-Robo-Request: 1} 헤더가 있어야 통과(다른 사이트가 몰래 보내는 요청 방어). 화면은 {@code static/index.html}.
 */
package com.myrobo.web;

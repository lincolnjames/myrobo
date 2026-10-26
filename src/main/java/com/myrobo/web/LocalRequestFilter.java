package com.myrobo.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** 모든 요청에 {@link LocalRequestGuard} 규칙을 적용한다. 다른 무엇보다 먼저 돈다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        Optional<String> reject = LocalRequestGuard.check(http.getMethod(), http.getRequestURI(),
                http.getHeader("Host"), http.getHeader(LocalRequestGuard.HEADER));
        if (reject.isPresent()) {
            HttpServletResponse out = (HttpServletResponse) res;
            out.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.setContentType("text/plain;charset=UTF-8");
            out.getOutputStream().write(("거부됨: " + reject.get()).getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(req, res);
    }
}

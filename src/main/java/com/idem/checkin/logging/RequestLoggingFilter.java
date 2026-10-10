package com.idem.checkin.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Одна строка лога на каждый HTTP-запрос: метод, путь, статус, время.
 * Query string и заголовки не пишутся (там референсы и токены); токен в ссылке визитки маскируется.
 * Health-пробы Kubernetes — только на TRACE, чтобы не забивать лог.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            String path = safePath(request.getRequestURI());
            int status = response.getStatus();
            if (path.equals("/health") || path.startsWith("/health/")) {
                log.trace("{} {} -> {} ({} ms)", request.getMethod(), path, status, millis);
            } else if (status >= 500) {
                log.warn("{} {} -> {} ({} ms)", request.getMethod(), path, status, millis);
            } else {
                log.info("{} {} -> {} ({} ms)", request.getMethod(), path, status, millis);
            }
        }
    }

    static String safePath(String path) {
        return path.startsWith("/c/") ? "/c/***" : path;
    }
}

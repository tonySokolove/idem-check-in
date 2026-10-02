package com.idem.checkin.checkin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** X-Correlation-Id: берётся из запроса (или генерируется), пишется в логи и возвращается в ответе. */
@Component
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

package com.flashstock.observability;

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

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String requested = request.getHeader("X-Correlation-ID");
        String id = requested != null && SAFE_ID.matcher(requested).matches() ? requested : UUID.randomUUID().toString();
        MDC.put("correlationId", id);
        response.setHeader("X-Correlation-ID", id);
        try { chain.doFilter(request, response); }
        finally { MDC.remove("correlationId"); }
    }
}

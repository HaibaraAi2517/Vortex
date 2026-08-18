package com.vortex.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final VortexSecurityProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String principal = principalName(request);
        long minute = Instant.now().getEpochSecond() / 60L;
        String key = principal + ':' + request.getRemoteAddr();
        Window window = windows.compute(key, (ignored, existing) ->
                existing == null || existing.minute != minute ? new Window(minute) : existing);

        if (window.requests.incrementAndGet() > properties.getRequestsPerMinute()) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            response.getWriter().write(
                    "{\"error\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"API request quota exceeded\"}");
            audit(request, response, principal, "rate-limited");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (isMutation(request.getMethod())) {
                audit(request, response, principal, "completed");
            }
            if (windows.size() > 10_000) {
                windows.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1L);
            }
        }
    }

    private String principalName(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated()
                ? "anonymous"
                : authentication.getName();
    }

    private boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private void audit(
            HttpServletRequest request,
            HttpServletResponse response,
            String principal,
            String outcome) {
        if (properties.isAuditEnabled()) {
            log.info(
                    "audit principal={} method={} path={} status={} outcome={} remote={}",
                    principal,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    outcome,
                    request.getRemoteAddr());
        }
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger requests = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}

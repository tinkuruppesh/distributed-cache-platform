package com.distributedcache.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Audits every state-changing CONTROL-PLANE call (nodes, simulations, settings, advisor). Cache reads/writes
 * (data plane) are deliberately not audited one by one: that would put a database write on the hot path.
 */
public class AuditFilter extends OncePerRequestFilter {
    private final AuditService audit;

    public AuditFilter(AuditService audit) { this.audit = audit; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return "GET".equals(req.getMethod()) || "OPTIONS".equals(req.getMethod()) || "HEAD".equals(req.getMethod())
                || !path.startsWith("/api/v1/") || path.startsWith("/api/v1/cache")
                || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        } finally {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            String user = a != null && a.isAuthenticated() ? a.getName() : "anonymous";
            int s = res.getStatus();
            String result = s < 400 ? "SUCCESS (" + s + ")" : "DENIED/FAILED (" + s + ")";
            String action = req.getMethod() + " " + req.getRequestURI();
            audit.record(user, action, req.getRequestURI(), result, req.getRemoteAddr(),
                    "Administrative request " + (s < 400 ? "completed" : "was denied or failed") + " with HTTP " + s + ".",
                    req.getQueryString() == null ? null : "query=" + req.getQueryString());
        }
    }
}

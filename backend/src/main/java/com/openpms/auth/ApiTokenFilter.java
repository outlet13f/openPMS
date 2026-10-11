package com.openpms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests with "Authorization: Bearer opms_…" act as the token's owner for that request only (no session, no cookie, so
 * no CSRF token either). A read token may only read; no token reaches sign-in, password or token management; each token
 * gets 300 requests a minute.
 */
public class ApiTokenFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "openpms.apiToken";
    private static final Set<String> READ = Set.of("GET", "HEAD", "OPTIONS");
    private static final List<String> CLOSED = List.of("/api/v1/auth/", "/api/v1/me/password", "/api/v1/me/api-tokens", "/api/v1/admin/api-tokens");

    private final ApiTokens tokens;
    private final RateLimiter limiter;
    private final JsonMapper json;

    public ApiTokenFilter(ApiTokens tokens, RateLimiter limiter, JsonMapper json) {
        this.tokens = tokens;
        this.limiter = limiter;
        this.json = json;
    }

    public static boolean isBearer(HttpServletRequest req) {
        var h = req.getHeader("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var path = req.getRequestURI().substring(req.getContextPath().length());
        if (!isBearer(req) || !path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }
        if (!tokens.enabled()) {
            SecurityConfig.problem(res, json, 401, "API 토큰을 쓰지 않도록 설정되어 있습니다.");
            return;
        }
        var bearer = tokens.resolve(req.getHeader("Authorization").substring(7).trim());
        if (bearer == null) {
            SecurityConfig.problem(res, json, 401, "API 토큰이 올바르지 않거나 만료되었습니다.");
            return;
        }
        if (CLOSED.stream().anyMatch(path::startsWith) && !path.equals("/api/v1/auth/me")) {
            SecurityConfig.problem(res, json, 403, "API 토큰으로는 로그인·비밀번호·토큰을 다룰 수 없습니다.");
            return;
        }
        if ("read".equals(bearer.scope()) && !READ.contains(req.getMethod()) && !path.equals("/api/v1/work-packages/query")
                && !path.equals("/api/v1/work-packages/export") && !path.equals("/api/v1/work-packages/export/count")) {
            SecurityConfig.problem(res, json, 403, "읽기 전용 토큰입니다.");
            return;
        }
        try {
            limiter.check("token:" + bearer.tokenId(), 300);
        } catch (com.openpms.common.ApiException e) {
            SecurityConfig.problem(res, json, 429, "요청이 너무 많습니다.");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(bearer.userId().toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
        req.setAttribute(ATTRIBUTE, bearer);
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

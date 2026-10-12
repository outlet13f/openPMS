package com.openpms.auth;

import com.openpms.common.ProblemAdvice;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Session login with an HttpOnly cookie (Spring Session JDBC) and a CSRF cookie token for the SPA
 * (architecture.md §1 인증). Everything under /api needs a session except signing in and the token flows; integrations send
 * a personal API token instead (ApiTokenFilter).
 */
@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository repo, JsonMapper json, OidcLogin oidc, ApiTokens tokens,
                                           RateLimiter limiter) throws Exception {
        http
                // a request with an API token carries no cookie, so there is nothing for CSRF to protect (ApiTokenFilter)
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(ApiTokenFilter::isBearer))
                .addFilterBefore(new ApiTokenFilter(tokens, limiter, json), org.springframework.security.web.csrf.CsrfFilter.class)
                .securityContext(sc -> sc.securityContextRepository(repo))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
                // an API has no page to return to after signing in: do not create sessions for 401s
                .requestCache(c -> c.disable())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // SSO: /api/v1/auth/oidc/start/{slug} → the provider → /api/v1/auth/oidc/callback/{slug} (OidcLogin)
                .oauth2Login(o -> o
                        .loginPage("/login") // the SPA's page; also keeps Spring from generating one
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(oidc.resolver()))
                        .redirectionEndpoint(r -> r.baseUri(OidcProviders.CALLBACK + "*"))
                        .authorizedClientRepository(OidcLogin.noTokenStore())
                        .successHandler(oidc)
                        .failureHandler(oidc))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/password-reset", "/api/v1/auth/password-reset/confirm", "/api/v1/auth/password-change", "/api/v1/auth/password-policy",
                                "/api/v1/auth/invitations/**", "/api/v1/auth/sso-providers", "/api/v1/auth/oidc/**", "/api/v1/openapi.json").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, json, 401, "로그인이 필요합니다."))
                        .accessDeniedHandler((req, res, ex) -> problem(res, json, 403, "요청을 확인할 수 없습니다. 페이지를 새로 고친 뒤 다시 시도하세요.")));
        return http.build();
    }

    static void problem(HttpServletResponse res, JsonMapper json, int status, String title) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        res.getWriter().write(json.writeValueAsString(ProblemAdvice.body(status, title, null, null, null)));
    }
}

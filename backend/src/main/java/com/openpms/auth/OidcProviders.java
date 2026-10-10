package com.openpms.auth;

import static com.openpms.db.Tables.OIDC_PROVIDERS;

import com.openpms.db.tables.records.OidcProvidersRecord;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;

/**
 * The OpenID Connect providers an administrator registered (관리 · 로그인과 비밀번호 · SSO), as Spring Security client
 * registrations keyed by slug. A registration needs the provider's discovery document, so it is built on first use
 * and cached; editing or removing a provider drops it from the cache. A provider that is off, unknown or unreachable
 * has no registration, and its login button then answers with "unavailable" instead of an error page.
 */
@Component
public class OidcProviders implements ClientRegistrationRepository {
    public static final String START = "/api/v1/auth/oidc/start/";
    public static final String CALLBACK = "/api/v1/auth/oidc/callback/";
    private static final Logger log = LoggerFactory.getLogger(OidcProviders.class);

    private final DSLContext db;
    private final String baseUrl;
    private final Map<String, ClientRegistration> cache = new ConcurrentHashMap<>();

    public OidcProviders(DSLContext db, @Value("${openpms.base-url}") String baseUrl) {
        this.db = db;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** What the identity provider must allow as the redirect URI for this slug. */
    public String redirectUri(String slug) {
        return baseUrl + CALLBACK + slug;
    }

    public OidcProvidersRecord bySlug(String slug) {
        return db.selectFrom(OIDC_PROVIDERS).where(OIDC_PROVIDERS.SLUG.eq(slug)).fetchOne();
    }

    public List<OidcProvidersRecord> all() {
        return db.selectFrom(OIDC_PROVIDERS).orderBy(OIDC_PROVIDERS.POSITION, OIDC_PROVIDERS.DISPLAY_NAME).fetch();
    }

    public void forget(String slug) {
        cache.remove(slug);
    }

    /**
     * Reads the discovery document at {issuer}/.well-known/openid-configuration (and checks its issuer is this one).
     * Throws when the provider cannot be reached or does not answer as an OpenID provider.
     */
    public ClientRegistration.Builder discover(String issuer) {
        return ClientRegistrations.fromIssuerLocation(issuer);
    }

    @Override
    public ClientRegistration findByRegistrationId(String slug) {
        var hit = cache.get(slug);
        if (hit != null) return hit;
        var p = bySlug(slug);
        if (p == null || !p.getEnabled()) return null;
        try {
            var r = discover(p.getIssuer())
                    .registrationId(slug)
                    .clientName(p.getDisplayName())
                    .clientId(p.getClientId())
                    .clientSecret(p.getClientSecret())
                    .scope(p.getScopes().trim().split("[\\s,]+"))
                    .redirectUri(redirectUri(slug))
                    .userNameAttributeName("sub")
                    .build();
            cache.put(slug, r);
            return r;
        } catch (RuntimeException e) {
            // the login button reports "unavailable"; the next try asks the provider again
            log.warn("OpenID provider {} unavailable: {}", slug, e.getMessage());
            return null;
        }
    }
}

package com.openpms.auth;

import static com.openpms.db.Tables.OIDC_PROVIDERS;
import static com.openpms.db.Tables.USER_IDENTITIES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Current;
import com.openpms.db.tables.records.OidcProvidersRecord;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** SSO providers: the login page's buttons (public) and their administration (관리 · 로그인과 비밀번호). */
@RestController
public class OidcController {
    /** A login button. */
    public record SsoProvider(String slug, String displayName) {}

    /** A provider as the administration shows it. The client secret never leaves the server. */
    public record AdminProvider(UUID id, String slug, String displayName, String issuer, String clientId, boolean secretSet, String scopes,
                                String claimEmail, String claimName, boolean autoCreate, boolean enabled, String redirectUri, int linkedUsers) {}

    private final DSLContext db;
    private final OidcProviders providers;
    private final Current current;

    public OidcController(DSLContext db, OidcProviders providers, Current current) {
        this.db = db;
        this.providers = providers;
        this.current = current;
    }

    @GetMapping("/api/v1/auth/sso-providers")
    public List<SsoProvider> buttons() {
        return providers.all().stream().filter(OidcProvidersRecord::getEnabled).map(p -> new SsoProvider(p.getSlug(), p.getDisplayName())).toList();
    }

    /** Reached only when Spring Security had no registration for the slug: off, unknown, or the provider is unreachable. */
    @GetMapping(OidcProviders.START + "{slug}")
    public ResponseEntity<Void> unavailable(@PathVariable String slug) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/login?sso_error=unavailable")).build();
    }

    private void admin() {
        if (!current.ctx().isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
    }

    private AdminProvider toAdmin(OidcProvidersRecord p) {
        int linked = db.fetchCount(USER_IDENTITIES, USER_IDENTITIES.PROVIDER_ID.eq(p.getId()));
        return new AdminProvider(p.getId(), p.getSlug(), p.getDisplayName(), p.getIssuer(), p.getClientId(), !p.getClientSecret().isEmpty(), p.getScopes(),
                p.getClaimEmail(), p.getClaimName(), p.getAutoCreate(), p.getEnabled(), providers.redirectUri(p.getSlug()), linked);
    }

    private OidcProvidersRecord find(UUID id) {
        var p = db.selectFrom(OIDC_PROVIDERS).where(OIDC_PROVIDERS.ID.eq(id)).fetchOne();
        if (p == null) throw ApiException.notFound("SSO 공급자를 찾을 수 없습니다.");
        return p;
    }

    @GetMapping("/api/v1/admin/oidc-providers")
    public List<AdminProvider> list() {
        admin();
        return providers.all().stream().map(this::toAdmin).toList();
    }

    @PostMapping("/api/v1/admin/oidc-providers")
    @Transactional
    public ResponseEntity<AdminProvider> create(@RequestBody Map<String, Object> in) {
        admin();
        var slug = text(in, "slug", 40);
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{0,39}")) throw ApiException.field("slug", "영문 소문자·숫자·하이픈으로 40자까지 입력하세요.");
        if (providers.bySlug(slug) != null) throw ApiException.field("slug", "이미 쓰는 식별자입니다.");
        var r = db.newRecord(OIDC_PROVIDERS);
        r.setSlug(slug);
        r.setPosition(db.fetchCount(OIDC_PROVIDERS));
        r.setAutoCreate(false);
        r.setEnabled(true);
        apply(r, in, true);
        r.store();
        providers.forget(slug);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAdmin(r));
    }

    @PatchMapping("/api/v1/admin/oidc-providers/{id}")
    @Transactional
    public AdminProvider update(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        var r = find(id);
        apply(r, in, false);
        r.store();
        providers.forget(r.getSlug());
        return toAdmin(r);
    }

    /** People keep their accounts; those without a password then sign in through a password reset or another provider. */
    @DeleteMapping("/api/v1/admin/oidc-providers/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        admin();
        var r = find(id);
        r.delete();
        providers.forget(r.getSlug());
        return ResponseEntity.noContent().build();
    }

    /** Applies the given fields (all of them on create); a new issuer must answer with a valid discovery document. */
    private void apply(OidcProvidersRecord r, Map<String, Object> in, boolean create) {
        if (create || In.has(in, "displayName")) {
            var v = text(in, "displayName", 60);
            if (v == null) throw ApiException.field("displayName", "로그인 버튼에 보일 이름을 60자까지 입력하세요.");
            r.setDisplayName(v);
        }
        if (create || In.has(in, "clientId")) {
            var v = text(in, "clientId", 255);
            if (v == null) throw ApiException.field("clientId", "클라이언트 ID를 입력하세요.");
            r.setClientId(v);
        }
        // blank on edit: keep the stored secret
        var secret = In.str(in, "clientSecret");
        if (secret != null && !secret.isBlank()) {
            if (secret.length() > 1000) throw ApiException.field("clientSecret", "1000자까지 입력할 수 있습니다.");
            r.setClientSecret(secret.trim());
        } else if (create) {
            throw ApiException.field("clientSecret", "클라이언트 시크릿을 입력하세요.");
        }
        if (create || In.has(in, "scopes")) {
            var v = text(in, "scopes", 500);
            if (v == null) v = "openid email profile";
            if (!List.of(v.split("[\\s,]+")).contains("openid")) throw ApiException.field("scopes", "openid가 들어 있어야 합니다.");
            r.setScopes(v);
        }
        for (var key : List.of("claimEmail", "claimName")) {
            if (!create && !In.has(in, key)) continue;
            var v = text(in, key, 100);
            if (v == null) v = key.equals("claimEmail") ? "email" : "name";
            if (key.equals("claimEmail")) r.setClaimEmail(v);
            else r.setClaimName(v);
        }
        if (In.has(in, "autoCreate")) r.setAutoCreate(Boolean.TRUE.equals(In.bool(in, "autoCreate")));
        if (In.has(in, "enabled")) r.setEnabled(!Boolean.FALSE.equals(In.bool(in, "enabled")));
        if (create || In.has(in, "issuer")) {
            var v = text(in, "issuer", 500);
            if (v == null || !v.matches("https?://\\S+")) throw ApiException.field("issuer", "https://로 시작하는 주소를 입력하세요.");
            // kept exactly as typed: the discovery document must name the same issuer, trailing slash included
            if (create || !v.equals(r.getIssuer())) {
                try {
                    providers.discover(v);
                } catch (RuntimeException e) {
                    throw ApiException.field("issuer", "발견 문서(" + v + "/.well-known/openid-configuration)를 읽을 수 없습니다. 주소와 네트워크를 확인하세요.");
                }
            }
            r.setIssuer(v);
        }
    }

    private static String text(Map<String, Object> in, String key, int max) {
        var v = In.str(in, key);
        if (v == null || v.isBlank()) return null;
        v = v.trim();
        if (v.length() > max) throw ApiException.field(key, max + "자까지 입력할 수 있습니다.");
        return v;
    }
}

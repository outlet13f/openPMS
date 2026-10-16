package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_IDENTITIES;
import static com.openpms.db.Tables.USER_PREFERENCES;

import com.openpms.db.tables.records.OidcProvidersRecord;
import com.openpms.db.tables.records.UsersRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signing in with a company OpenID provider. Spring Security runs the authorization code flow (state, nonce, PKCE,
 * ID token signature and issuer); this class decides who the person is in openPMS:
 * <ol>
 *   <li>an account already linked to this provider's subject;</li>
 *   <li>else the account with the same e-mail address, which gets linked (unless the provider says the address is
 *       not verified);</li>
 *   <li>else a new active account, when the provider may create accounts;</li>
 *   <li>else no sign-in: an administrator has to invite the person first.</li>
 * </ol>
 * Locked accounts stay locked. An invited person who signs in this way has proven the address and becomes active.
 * Failures go back to the login page as {@code /login?sso_error=code}; nothing about the account is put in the URL.
 */
@Component
public class OidcLogin implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(OidcLogin.class);
    private static final String NEXT = "openpms.sso.next";

    /** Why a sign-in was refused; the code goes to the login page, which explains it. */
    static final class Refused extends RuntimeException {
        final String code;

        Refused(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private final DSLContext db;
    private final OidcProviders providers;
    private final SecurityContextRepository contextRepo;
    private final TransactionTemplate tx;

    public OidcLogin(DSLContext db, OidcProviders providers, SecurityContextRepository contextRepo, TransactionTemplate tx) {
        this.db = db;
        this.providers = providers;
        this.contextRepo = contextRepo;
        this.tx = tx;
    }

    /** /api/v1/auth/oidc/start/{slug}[?next=/path]: PKCE on, and the page to come back to kept in the session. */
    public OAuth2AuthorizationRequestResolver resolver() {
        var base = new DefaultOAuth2AuthorizationRequestResolver(providers, OidcProviders.START.replaceAll("/$", ""));
        base.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest req) {
                var path = req.getRequestURI().substring(req.getContextPath().length());
                // off, unknown or unreachable: no redirect here, OidcController answers "unavailable"
                if (path.startsWith(OidcProviders.START) && providers.findByRegistrationId(path.substring(OidcProviders.START.length())) == null) return null;
                var r = base.resolve(req);
                if (r != null) {
                    var next = req.getParameter("next");
                    // only same-origin paths are a return target
                    if (next != null && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\")) req.getSession().setAttribute(NEXT, next);
                    else req.getSession().removeAttribute(NEXT);
                }
                return r;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest req, String registrationId) {
                return providers.findByRegistrationId(registrationId) == null ? null : base.resolve(req, registrationId);
            }
        };
    }

    /** The provider's tokens are only used to sign in: they are not kept anywhere. */
    public static OAuth2AuthorizedClientRepository noTokenStore() {
        return new OAuth2AuthorizedClientRepository() {
            @Override
            public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal, HttpServletRequest req) {
                return null;
            }

            @Override
            public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal, HttpServletRequest req, HttpServletResponse res) {}

            @Override
            public void removeAuthorizedClient(String id, Authentication principal, HttpServletRequest req, HttpServletResponse res) {}
        };
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth) throws IOException {
        var token = (OAuth2AuthenticationToken) auth;
        var slug = token.getAuthorizedClientRegistrationId();
        UUID userId;
        try {
            userId = tx.execute(s -> resolveUser(slug, (OidcUser) token.getPrincipal()));
        } catch (Refused e) {
            log.info("SSO sign-in through {} refused: {}", slug, e.code);
            refuse(req, res, e.code);
            return;
        }
        var session = req.getSession();
        var next = (String) session.getAttribute(NEXT);
        session.removeAttribute(NEXT);
        // the session holds openPMS's own sign-in, as after a password login (Current reads the user id from it)
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of()));
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, req, res);
        res.sendRedirect(next != null ? next : "/");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        var code = e instanceof OAuth2AuthenticationException o ? o.getError().getErrorCode() : "";
        // the provider says the person cancelled or was not allowed: that is not a fault of the setup
        var reason = "access_denied".equals(code) ? "cancelled" : "failed";
        log.warn("SSO sign-in failed: {} {}", code, e.getMessage());
        refuse(req, res, reason);
    }

    private void refuse(HttpServletRequest req, HttpServletResponse res, String code) throws IOException {
        var s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        res.sendRedirect("/login?sso_error=" + code);
    }

    UUID resolveUser(String slug, OidcUser person) {
        var p = providers.bySlug(slug);
        if (p == null || !p.getEnabled()) throw new Refused("unavailable");
        var subject = person.getSubject();
        var now = OffsetDateTime.now();
        var linked = db.select(USERS.fields()).from(USER_IDENTITIES).join(USERS).on(USERS.ID.eq(USER_IDENTITIES.USER_ID))
                .where(USER_IDENTITIES.PROVIDER_ID.eq(p.getId()), USER_IDENTITIES.SUBJECT.eq(subject)).fetchOneInto(UsersRecord.class);
        var user = linked;
        if (user == null) {
            var email = claim(person, p.getClaimEmail());
            if (email == null || !email.contains("@") || email.length() > 254) throw new Refused("no_email");
            user = db.selectFrom(USERS).where(USERS.EMAIL.eq(email)).fetchOne();
            if (user != null) {
                if (Boolean.FALSE.equals(person.getEmailVerified())) throw new Refused("unverified");
            } else if (p.getAutoCreate()) {
                user = db.insertInto(USERS).set(USERS.EMAIL, email).set(USERS.NAME, nameOf(person, p, email)).set(USERS.STATUS, "active")
                        .returning().fetchOne();
                db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, user.getId()).onConflictDoNothing().execute();
            } else {
                throw new Refused("no_account");
            }
            if ("deleted".equals(user.getStatus())) throw new Refused("no_account");
            // one account per provider: a second subject for the same account replaces the first
            db.deleteFrom(USER_IDENTITIES).where(USER_IDENTITIES.PROVIDER_ID.eq(p.getId()), USER_IDENTITIES.USER_ID.eq(user.getId())).execute();
            db.insertInto(USER_IDENTITIES).set(USER_IDENTITIES.USER_ID, user.getId()).set(USER_IDENTITIES.PROVIDER_ID, p.getId())
                    .set(USER_IDENTITIES.SUBJECT, subject).execute();
        }
        switch (user.getStatus()) {
            case "locked", "deleted" -> throw new Refused(user.getStatus().equals("locked") ? "locked" : "no_account");
            case "invited" -> {
                db.update(USERS).set(USERS.STATUS, "active").where(USERS.ID.eq(user.getId())).execute();
                db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, user.getId()).onConflictDoNothing().execute();
            }
            default -> {}
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) throw new Refused("locked");
        db.update(USERS).set(USERS.LAST_LOGIN_AT, now).set(USERS.FAILED_LOGIN_COUNT, 0).where(USERS.ID.eq(user.getId())).execute();
        db.update(USER_IDENTITIES).set(USER_IDENTITIES.LAST_LOGIN_AT, now)
                .where(USER_IDENTITIES.PROVIDER_ID.eq(p.getId()), USER_IDENTITIES.USER_ID.eq(user.getId())).execute();
        return user.getId();
    }

    private static String claim(OidcUser person, String name) {
        var v = person.getAttributes().get(name);
        if (v == null) v = person.getClaims().get(name);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String nameOf(OidcUser person, OidcProvidersRecord p, String email) {
        var name = claim(person, p.getClaimName());
        if (name == null || name.isBlank()) {
            var family = claim(person, "family_name") == null ? "" : claim(person, "family_name");
            var given = claim(person, "given_name") == null ? "" : claim(person, "given_name");
            // Korean names: family name first, no space (김서연); others: given name first (Jane Doe)
            name = (family + given).matches(".*\\p{IsHangul}.*") ? family + given : (given + " " + family).trim();
        }
        if (name.isBlank()) name = email.substring(0, email.indexOf('@'));
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}

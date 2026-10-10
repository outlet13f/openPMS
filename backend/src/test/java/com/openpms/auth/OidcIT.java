package com.openpms.auth;

import static com.openpms.db.Tables.OIDC_PROVIDERS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_IDENTITIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSO through a company OpenID provider, against a small provider running in the test (discovery, token, userinfo,
 * JWKS; signed ID tokens; PKCE checked). Who signs in: linked account, then same e-mail, then a new account if allowed.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class OidcIT {
    /** The identity provider: hands out a code per test sign-in, then tokens for it. */
    static final class Idp {
        record Pending(Map<String, Object> claims, String nonce, String challenge, boolean badSignature) {}

        final HttpServer server;
        final String issuer;
        final RSAKey key;
        final RSAKey otherKey;
        final Map<String, Pending> codes = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> userinfo = new ConcurrentHashMap<>();
        final JsonMapper json = JsonMapper.builder().build();

        Idp() throws Exception {
            key = new RSAKeyGenerator(2048).keyID("k1").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/.well-known/openid-configuration", ex -> send(ex, 200, Map.of(
                    "issuer", issuer, "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token",
                    "userinfo_endpoint", issuer + "/userinfo", "jwks_uri", issuer + "/jwks", "response_types_supported", new String[] {"code"},
                    "subject_types_supported", new String[] {"public"}, "id_token_signing_alg_values_supported", new String[] {"RS256"},
                    "token_endpoint_auth_methods_supported", new String[] {"client_secret_basic"})));
            server.createContext("/jwks", ex -> send(ex, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
            server.createContext("/token", this::token);
            server.createContext("/userinfo", ex -> {
                var auth = ex.getRequestHeaders().getFirst("Authorization");
                var info = auth == null ? null : userinfo.get(auth.replace("Bearer ", ""));
                send(ex, info == null ? 401 : 200, info == null ? Map.of("error", "invalid_token") : info);
            });
            server.start();
        }

        void token(HttpExchange ex) throws IOException {
            var form = new HashMap<String, String>();
            for (var pair : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                var kv = pair.split("=", 2);
                form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            var p = codes.remove(form.get("code"));
            if (p == null || !p.challenge().equals(s256(form.get("code_verifier")))) {
                send(ex, 400, Map.of("error", "invalid_grant"));
                return;
            }
            try {
                var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("openpms-test").issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("nonce", p.nonce());
                p.claims().forEach(claims::claim);
                var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
                jwt.sign(new RSASSASigner(p.badSignature() ? otherKey : key));
                var access = "at-" + form.get("code");
                userinfo.put(access, p.claims());
                send(ex, 200, Map.of("access_token", access, "token_type", "Bearer", "expires_in", 300, "id_token", jwt.serialize(), "scope", "openid email profile"));
            } catch (Exception e) {
                send(ex, 500, Map.of("error", "server_error"));
            }
        }

        static String s256(String verifier) {
            if (verifier == null) return "";
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void send(HttpExchange ex, int status, Object body) throws IOException {
            var bytes = json.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }

    static Idp idp;

    @BeforeAll
    static void startIdp() throws Exception {
        idp = new Idp();
    }

    @AfterAll
    static void stopIdp() {
        idp.server.stop(0);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    OidcProviders providers;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        // the test database is shared by every test class: leave no provider, link or account behind
        for (var slug : db.select(OIDC_PROVIDERS.SLUG).from(OIDC_PROVIDERS).fetch(OIDC_PROVIDERS.SLUG)) providers.forget(slug);
        db.deleteFrom(OIDC_PROVIDERS).execute();
        db.deleteFrom(USERS).where(USERS.EMAIL.like("%@sso.example")).execute();
    }

    void provider(boolean autoCreate) {
        db.insertInto(OIDC_PROVIDERS).set(OIDC_PROVIDERS.SLUG, "company").set(OIDC_PROVIDERS.DISPLAY_NAME, "사내 계정").set(OIDC_PROVIDERS.ISSUER, idp.issuer)
                .set(OIDC_PROVIDERS.CLIENT_ID, "openpms-test").set(OIDC_PROVIDERS.CLIENT_SECRET, "test-secret").set(OIDC_PROVIDERS.AUTO_CREATE, autoCreate).execute();
        providers.forget("company");
    }

    /** The whole round trip; returns where openPMS sends the browser at the end. */
    String signIn(MockHttpSession s, Map<String, Object> claims, boolean badSignature) throws Exception {
        var start = mvc.perform(get(OidcProviders.START + "company").param("next", "/projects").session(s)).andExpect(status().isFound()).andReturn();
        var authorize = UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build();
        assertThat(authorize.toUriString()).startsWith(idp.issuer + "/authorize");
        var q = authorize.getQueryParams();
        assertThat(q.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(URLDecoder.decode(q.getFirst("redirect_uri"), StandardCharsets.UTF_8)).endsWith(OidcProviders.CALLBACK + "company");
        var code = "code-" + System.nanoTime();
        idp.codes.put(code, new Idp.Pending(claims, URLDecoder.decode(q.getFirst("nonce"), StandardCharsets.UTF_8), URLDecoder.decode(q.getFirst("code_challenge"), StandardCharsets.UTF_8), badSignature));
        var back = mvc.perform(get(OidcProviders.CALLBACK + "company").param("code", code).param("state", URLDecoder.decode(q.getFirst("state"), StandardCharsets.UTF_8)).session(s))
                .andExpect(status().isFound()).andReturn();
        return back.getResponse().getRedirectedUrl();
    }

    String signIn(MockHttpSession s, Map<String, Object> claims) throws Exception {
        return signIn(s, claims, false);
    }

    @Test
    void anExistingAccountIsLinkedByEmailThenFoundBySubject() throws Exception {
        provider(false);
        var s = new MockHttpSession();
        assertThat(signIn(s, Map.of("sub", "u-100", "email", "haneul.lee@openpms.local", "name", "이하늘"))).isEqualTo("/projects");
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("haneul.lee@openpms.local"));
        // the provider changed the address: the link by subject still finds the same person
        var again = new MockHttpSession();
        signIn(again, Map.of("sub", "u-100", "email", "renamed@sso.example"));
        mvc.perform(get("/api/v1/auth/me").session(again)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("haneul.lee@openpms.local"));
        assertThat(db.fetchCount(USER_IDENTITIES, USER_IDENTITIES.SUBJECT.eq("u-100"))).isEqualTo(1);
    }

    @Test
    void someoneWithoutAnAccountNeedsAnInvitationUnlessTheProviderCreatesAccounts() throws Exception {
        provider(false);
        var s = new MockHttpSession();
        assertThat(signIn(s, Map.of("sub", "u-200", "email", "newcomer@sso.example"))).isEqualTo("/login?sso_error=no_account");
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isUnauthorized());
        assertThat(db.fetchCount(USERS, USERS.EMAIL.eq("newcomer@sso.example"))).isZero();

        db.update(OIDC_PROVIDERS).set(OIDC_PROVIDERS.AUTO_CREATE, true).execute();
        var t = new MockHttpSession();
        assertThat(signIn(t, Map.of("sub", "u-200", "email", "newcomer@sso.example", "family_name", "정", "given_name", "다은"))).isEqualTo("/projects");
        mvc.perform(get("/api/v1/auth/me").session(t)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("정다은"))
                .andExpect(jsonPath("$.isAdmin").value(false));
        var u = db.selectFrom(USERS).where(USERS.EMAIL.eq("newcomer@sso.example")).fetchOne();
        assertThat(u.getStatus()).isEqualTo("active");
        assertThat(u.getPasswordHash()).isNull();
        // no password: the form cannot sign this account in
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"newcomer@sso.example\",\"password\":\"\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void lockedAccountsAndUnverifiedAddressesAreRefused() throws Exception {
        provider(false);
        var locked = db.select(USERS.EMAIL).from(USERS).where(USERS.STATUS.eq("locked")).limit(1).fetchOne(USERS.EMAIL);
        assertThat(signIn(new MockHttpSession(), Map.of("sub", "u-300", "email", locked))).isEqualTo("/login?sso_error=locked");
        assertThat(signIn(new MockHttpSession(), Map.of("sub", "u-301", "email", "haneul.lee@openpms.local", "email_verified", false)))
                .isEqualTo("/login?sso_error=unverified");
        assertThat(signIn(new MockHttpSession(), Map.of("sub", "u-302"))).isEqualTo("/login?sso_error=no_email");
        assertThat(db.fetchCount(USER_IDENTITIES)).isZero();
    }

    @Test
    void aTokenSignedWithAnotherKeyOrAForeignStateDoesNotSignIn() throws Exception {
        provider(true);
        var s = new MockHttpSession();
        assertThat(signIn(s, Map.of("sub", "u-400", "email", "forged@sso.example"), true)).isEqualTo("/login?sso_error=failed");
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isUnauthorized());
        // a callback this browser did not start
        mvc.perform(get(OidcProviders.CALLBACK + "company").param("code", "x").param("state", "not-mine").session(new MockHttpSession()))
                .andExpect(status().isFound()).andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=failed"));
        // the person cancelled at the provider
        mvc.perform(get(OidcProviders.CALLBACK + "company").param("error", "access_denied").param("state", "x").session(new MockHttpSession()))
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).startsWith("/login?sso_error="));
        assertThat(db.fetchCount(USERS, USERS.EMAIL.eq("forged@sso.example"))).isZero();
    }

    @Test
    void administratorsManageProvidersAndTheSecretNeverComesBack() throws Exception {
        var admin = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        var body = "{\"slug\":\"company\",\"displayName\":\"사내 계정\",\"issuer\":\"%s\",\"clientId\":\"openpms-test\",\"clientSecret\":\"s3cret\"}";
        mvc.perform(post("/api/v1/admin/oidc-providers").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("http://127.0.0.1:1/nothing"))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("issuer"));
        var created = mvc.perform(post("/api/v1/admin/oidc-providers").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(idp.issuer))).andExpect(status().isCreated()).andExpect(jsonPath("$.secretSet").value(true))
                .andExpect(jsonPath("$.clientSecret").doesNotExist()).andExpect(jsonPath("$.redirectUri").value(org.hamcrest.Matchers.endsWith("/api/v1/auth/oidc/callback/company")))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("s3cret");
        var id = JsonMapper.builder().build().readTree(created).get("id").asString();
        mvc.perform(post("/api/v1/admin/oidc-providers").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(idp.issuer))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("slug"));
        mvc.perform(get("/api/v1/auth/sso-providers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].displayName").value("사내 계정"));

        // a blank secret keeps the stored one; switching off hides the button and the start link
        mvc.perform(patch("/api/v1/admin/oidc-providers/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientSecret\":\"\",\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(db.select(OIDC_PROVIDERS.CLIENT_SECRET).from(OIDC_PROVIDERS).fetchOne(OIDC_PROVIDERS.CLIENT_SECRET)).isEqualTo("s3cret");
        mvc.perform(get("/api/v1/auth/sso-providers")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(OidcProviders.START + "company")).andExpect(status().isFound())
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=unavailable"));

        var member = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"haneul.lee@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/oidc-providers").session(member)).andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/admin/oidc-providers/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(db.fetchCount(OIDC_PROVIDERS)).isZero();
    }
}

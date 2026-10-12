package com.openpms.auth;

import static com.openpms.db.Tables.API_TOKENS;
import static com.openpms.db.Tables.IDEMPOTENCY_KEYS;
import static com.openpms.db.Tables.SETTINGS;
import static com.openpms.db.Tables.TEMPORARY_PASSWORDS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.common.IdempotencyFilter;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 임시 비밀번호: made without mail, issued again, looked up until changed, changed at the first sign-in, cleared by every
 * other password path, run out. The test database is shared by every class: each test makes its own accounts.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class TemporaryPasswordIT {
    static final String ADMIN = "seoyeon.kim@openpms.local";
    static final String DEV = "openpms!";
    static final String OWN = "my-own-pass-2026!";

    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;
    @Autowired
    Tokens tokens;
    @Autowired
    IdempotencyFilter idempotency;

    MockMvc mvc;

    record Made(UUID id, String password) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(idempotency).build();
    }

    static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@openpms.local";
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions signIn(MockHttpSession s, String email, String password) throws Exception {
        return send(s, post("/api/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    MockHttpSession login(String email, String password) throws Exception {
        var s = new MockHttpSession();
        signIn(s, email, password).andExpect(status().isOk());
        return s;
    }

    Made make(MockHttpSession admin, String email) throws Exception {
        var body = send(admin, post("/api/v1/admin/users"), "{\"name\":\"임시 확인\",\"email\":\"" + email + "\",\"isAdmin\":false,\"method\":\"temporaryPassword\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        return new Made(UUID.fromString(node.get("user").get("id").asString()), node.get("temporaryPassword").asString());
    }

    String issue(MockHttpSession admin, UUID id) throws Exception {
        var body = send(admin, post("/api/v1/admin/users/" + id + "/temporary-password"), "{}").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("temporaryPassword").asString();
    }

    /** MockMvc does not go through Spring Session: a stored session row stands for the person signed in elsewhere. */
    void fakeSession(UUID userId) {
        db.execute("insert into spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) "
                + "values (?, ?, 0, 0, 1800, ?, ?)", UUID.randomUUID().toString(), UUID.randomUUID().toString(), System.currentTimeMillis() + 1_800_000L, userId.toString());
    }

    int sessions(UUID userId) {
        return db.fetchCount(table("spring_session"), field("principal_name", String.class).eq(userId.toString()));
    }

    @Test
    void aNewAccountGetsATemporaryPasswordWithoutMailAndTheAnswerIsNotKept() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("made");
        var key = UUID.randomUUID().toString();
        var body = mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"임시 확인\",\"email\":\"" + email + "\",\"isAdmin\":false,\"method\":\"temporaryPassword\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.user.status").value("active")).andExpect(jsonPath("$.user.temporaryPasswordUntil").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        var id = UUID.fromString(node.get("user").get("id").asString());
        var password = node.get("temporaryPassword").asString();
        assertThat(password).hasSizeGreaterThanOrEqualTo(16);
        // no invitation was issued, so no mail went out
        assertThat(db.fetchCount(USER_TOKENS, USER_TOKENS.USER_ID.eq(id), USER_TOKENS.PURPOSE.eq("invitation"))).isZero();
        // the answer with the password is not kept for Idempotency-Key retries
        assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq(key))).isZero();
        assertThat(db.select(TEMPORARY_PASSWORDS.PASSWORD).from(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(id))
                .fetchOne(TEMPORARY_PASSWORDS.PASSWORD)).isEqualTo(password);
        // an unknown method is refused before anything is made
        var other = email("bad");
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"x\",\"email\":\"" + other + "\",\"isAdmin\":false,\"method\":\"sms\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("method"));
        assertThat(db.fetchCount(USERS, USERS.EMAIL.eq(other))).isZero();
    }

    @Test
    void issuingAgainActivatesAnInvitationClearsTheLockAndSignsThePersonOut() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("invited");
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"초대 대기\",\"email\":\"" + email + "\",\"isAdmin\":false}").andExpect(status().isCreated());
        var id = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
        var link = tokens.issue(id, "invitation", Duration.ofHours(1), null);
        var first = issue(admin, id);
        assertThat(db.select(USERS.STATUS).from(USERS).where(USERS.ID.eq(id)).fetchOne(USERS.STATUS)).isEqualTo("active");
        mvc.perform(get("/api/v1/auth/invitations/" + link)).andExpect(status().isGone());

        db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 3).set(USERS.LOCKED_UNTIL, OffsetDateTime.now().plusMinutes(10)).where(USERS.ID.eq(id)).execute();
        var reset = tokens.issue(id, "password_reset", Duration.ofHours(1), null);
        fakeSession(id);
        var second = issue(admin, id);
        assertThat(second).isNotEqualTo(first);
        assertThat(sessions(id)).isZero();
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne();
        assertThat(u.getLockedUntil()).isNull();
        assertThat(u.getFailedLoginCount()).isZero();
        send(new MockHttpSession(), post("/api/v1/auth/password-reset/confirm"), "{\"token\":\"" + reset + "\",\"newPassword\":\"reset-pass-2026!\"}")
                .andExpect(status().isGone());

        mvc.perform(get("/api/v1/admin/users/" + id + "/temporary-password").session(admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.temporaryPassword").value(second)).andExpect(jsonPath("$.issuedBy.name").value("김서연"));
        // the list says until when, never the password
        mvc.perform(get("/api/v1/admin/users").session(admin).param("q", email)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].temporaryPasswordUntil").isNotEmpty()).andExpect(jsonPath("$[0].temporaryPassword").doesNotExist());
    }

    @Test
    void whoMayIssueOrLookAndWhatIsRefused() throws Exception {
        var admin = login(ADMIN, DEV);
        var adminId = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(ADMIN)).fetchOne(USERS.ID);
        var doyoon = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("doyoon.han@openpms.local")).fetchOne(USERS.ID);
        var role = json.readTree(send(admin, post("/api/v1/admin/roles"), "{\"name\":\"사용자 관리자(임시 비밀번호)\",\"scope\":\"global\",\"permissions\":[\"global.manage_users\"]}")
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asString();
        send(admin, put("/api/v1/admin/users/" + doyoon + "/global-roles"), "{\"roleIds\":[\"" + role + "\"]}").andExpect(status().isOk());
        try {
            var manager = login("doyoon.han@openpms.local", DEV);
            var member = make(admin, email("member"));
            // a user manager handles ordinary accounts, not an administrator's
            issue(manager, member.id());
            send(manager, post("/api/v1/admin/users/" + adminId + "/temporary-password"), "{}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("관리자 계정은 관리자만 다룰 수 있습니다."));
            mvc.perform(get("/api/v1/admin/users/" + adminId + "/temporary-password").session(manager)).andExpect(status().isForbidden());
            // not myself
            send(admin, post("/api/v1/admin/users/" + adminId + "/temporary-password"), "{}").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("내 비밀번호는 계정 설정에서 바꾸세요."));
            // someone without the right
            var plain = login("haneul.lee@openpms.local", DEV);
            send(plain, post("/api/v1/admin/users/" + member.id() + "/temporary-password"), "{}").andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/users/" + member.id() + "/temporary-password").session(plain)).andExpect(status().isForbidden());
            // a locked account
            send(admin, patch("/api/v1/admin/users/" + member.id()), "{\"status\":\"locked\"}").andExpect(status().isOk());
            send(admin, post("/api/v1/admin/users/" + member.id() + "/temporary-password"), "{}").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("잠긴 계정입니다. 먼저 잠금을 푸세요."));
            // a deleted account: gone, and its temporary password with it
            send(admin, delete("/api/v1/admin/users/" + member.id()), "{}").andExpect(status().isNoContent());
            assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(member.id()))).isZero();
            send(admin, post("/api/v1/admin/users/" + member.id() + "/temporary-password"), "{}").andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID() + "/temporary-password").session(admin)).andExpect(status().isNotFound());
        } finally {
            send(admin, put("/api/v1/admin/users/" + doyoon + "/global-roles"), "{\"roleIds\":[]}").andExpect(status().isOk());
        }
    }

    @Test
    void anApiTokenCannotMakeIssueOrLookUpATemporaryPassword() throws Exception {
        db.deleteFrom(SETTINGS).where(SETTINGS.KEY.eq("api.tokens_enabled")).execute(); // tokens on (the default), whatever ApiTokensIT left
        var admin = login(ADMIN, DEV);
        var member = make(admin, email("token"));
        var secret = json.readTree(send(admin, post("/api/v1/me/api-tokens"), "{\"name\":\"임시 비밀번호 시험\",\"scope\":\"write\",\"expiresInDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("secret").asString();
        var refused = "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.";
        try {
            mvc.perform(post("/api/v1/admin/users/" + member.id() + "/temporary-password").header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            mvc.perform(get("/api/v1/admin/users/" + member.id() + "/temporary-password").header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            mvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"토큰\",\"email\":\"" + email("bytoken") + "\",\"isAdmin\":false,\"method\":\"temporaryPassword\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
        } finally {
            // ApiTokensIT counts every token in force: leave none behind
            db.deleteFrom(API_TOKENS).where(API_TOKENS.NAME.eq("임시 비밀번호 시험")).execute();
        }
    }

    @Test
    void aPercentEncodedPathIsNoWayAroundTheTokenRefusalOrTheIdempotencyKeepOut() throws Exception {
        db.deleteFrom(SETTINGS).where(SETTINGS.KEY.eq("api.tokens_enabled")).execute();
        var admin = login(ADMIN, DEV);
        var member = make(admin, email("encoded"));
        var secret = json.readTree(send(admin, post("/api/v1/me/api-tokens"), "{\"name\":\"인코딩 시험\",\"scope\":\"write\",\"expiresInDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("secret").asString();
        try {
            // %74 is a "t": the same route for Spring, but not for a plain string match on the path
            var encoded = new URI("/api/v1/admin/users/" + member.id() + "/%74emporary-password");
            mvc.perform(get(encoded).header("Authorization", "Bearer " + secret)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다."));
            var key = UUID.randomUUID().toString();
            mvc.perform(post(encoded).session(admin).with(csrf()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
            assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq(key))).isZero();
        } finally {
            db.deleteFrom(API_TOKENS).where(API_TOKENS.NAME.eq("인코딩 시험")).execute();
        }
    }
}

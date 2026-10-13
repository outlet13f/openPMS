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
import com.openpms.jobs.Housekeeping;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
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
    @Autowired
    Housekeeping housekeeping;
    @Autowired
    TemporaryPasswords temporaryPasswords;

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
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
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
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
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
                .andExpect(header().string("Cache-Control", "no-store"))
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
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq(key))).isZero();
        } finally {
            db.deleteFrom(API_TOKENS).where(API_TOKENS.NAME.eq("인코딩 시험")).execute();
        }
    }

    /** Sign in with the temporary password: no session, a change token in the answer. */
    String changeToken(String email, String password) throws Exception {
        var s = new MockHttpSession();
        var body = signIn(s, email, password).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("새 비밀번호를 정해야 합니다."))
                .andExpect(jsonPath("$.current.code").value("password_change_required")).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isUnauthorized());
        return json.readTree(body).get("current").get("changeToken").asString();
    }

    ResultActions change(MockHttpSession s, String token, String password) throws Exception {
        return send(s, post("/api/v1/auth/password-change"), "{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
    }

    @Test
    void theFirstSignInAsksForAPasswordOfOnesOwn() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("first");
        var made = make(admin, email);
        var token = changeToken(email, made.password());
        // the same as the temporary one, or too weak: refused, and the token still works
        change(new MockHttpSession(), token, made.password()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("임시 비밀번호와 다른 비밀번호를 정하세요."));
        change(new MockHttpSession(), token, "short").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        fakeSession(made.id());
        var s = new MockHttpSession();
        change(s, token, OWN).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk());
        assertThat(sessions(made.id())).isZero(); // signed in elsewhere: out
        assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(made.id()))).isZero();
        change(new MockHttpSession(), token, "another-pass-2026!").andExpect(status().isGone()).andExpect(jsonPath("$.title").value("시간이 지났습니다."));
        signIn(new MockHttpSession(), email, made.password()).andExpect(status().isUnauthorized());
        signIn(new MockHttpSession(), email, OWN).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users/" + made.id() + "/temporary-password").session(admin)).andExpect(status().isNotFound());
    }

    @Test
    void aChangeTokenStopsWorkingWhenTheAccountIsLockedOrTheTenMinutesPass() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("lockedafter");
        var made = make(admin, email);
        var token = changeToken(email, made.password());
        send(admin, patch("/api/v1/admin/users/" + made.id()), "{\"status\":\"locked\"}").andExpect(status().isOk());
        change(new MockHttpSession(), token, OWN).andExpect(status().isGone());
        send(admin, patch("/api/v1/admin/users/" + made.id()), "{\"status\":\"active\"}").andExpect(status().isOk());
        var late = changeToken(email, made.password());
        db.update(USER_TOKENS).set(USER_TOKENS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(USER_TOKENS.USER_ID.eq(made.id()), USER_TOKENS.PURPOSE.eq("password_change"), USER_TOKENS.USED_AT.isNull()).execute();
        change(new MockHttpSession(), late, OWN).andExpect(status().isGone());
    }

    @Test
    void aChangeTokenDoesNotOutliveTheTemporaryPasswordItCameFrom() throws Exception {
        var admin = login(ADMIN, DEV);
        // the person set a password another way meanwhile (here a reset link): the temporary one is gone, and so is the token
        var email = email("outlived");
        var made = make(admin, email);
        var token = changeToken(email, made.password());
        var reset = tokens.issue(made.id(), "password_reset", Duration.ofHours(1), null);
        send(new MockHttpSession(), post("/api/v1/auth/password-reset/confirm"), "{\"token\":\"" + reset + "\",\"newPassword\":\"" + OWN + "\"}")
                .andExpect(status().isNoContent());
        change(new MockHttpSession(), token, "another-pass-2026!").andExpect(status().isGone());
        signIn(new MockHttpSession(), email, OWN).andExpect(status().isOk());
        // the temporary password ran out within the ten minutes
        var lateEmail = email("outlivedlate");
        var late = make(admin, lateEmail);
        var lateToken = changeToken(lateEmail, late.password());
        db.update(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(late.id())).execute();
        change(new MockHttpSession(), lateToken, OWN).andExpect(status().isGone());
    }

    @Test
    void aTemporaryPasswordRunsOutAtSignInAndInTheNightlyCleanup() throws Exception {
        var admin = login(ADMIN, DEV);
        // at sign-in
        var email = email("runout");
        var made = make(admin, email);
        db.update(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(made.id())).execute();
        signIn(new MockHttpSession(), email, made.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.current.code").value("temporary_password_expired"));
        assertThat(db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(made.id())).fetchOne(USERS.PASSWORD_HASH)).isNull();
        signIn(new MockHttpSession(), email, made.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.current").doesNotExist());
        mvc.perform(get("/api/v1/admin/users/" + made.id() + "/temporary-password").session(admin)).andExpect(status().isNotFound());
        // issued again, it works again
        var again = issue(admin, made.id());
        changeToken(email, again);

        // in the nightly cleanup (token-cleanup)
        var other = make(admin, email("nightly"));
        db.update(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(other.id())).execute();
        mvc.perform(get("/api/v1/admin/users").session(admin).param("q", "nightly")).andExpect(jsonPath("$[0].temporaryPasswordUntil").isNotEmpty());
        housekeeping.tokens(Instant.now());
        assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(other.id()))).isZero();
        assertThat(db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(other.id())).fetchOne(USERS.PASSWORD_HASH)).isNull();
        // a live one stays
        assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(made.id()))).isEqualTo(1);
    }

    /** A row as if one were issued meanwhile (the API would also change the password or end an invitation). */
    void putRow(UUID userId) {
        db.insertInto(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.USER_ID, userId).set(TEMPORARY_PASSWORDS.PASSWORD, "unused")
                .set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().plusHours(1)).execute();
    }

    int rows(UUID userId) {
        return db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(userId));
    }

    /** The sign-in checks the hash (~0.1 s) before it issues a change token: a reissue landing in between must not leave the old password a way in. */
    @Test
    void aChangeTokenIsOnlyIssuedForThePasswordThatWasChecked() throws Exception {
        var admin = login(ADMIN, DEV);
        var made = make(admin, email("stale"));
        var checked = db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(made.id())).fetchOne(USERS.PASSWORD_HASH);
        issue(admin, made.id()); // an administrator issues a new one while that sign-in was still hashing
        assertThat(temporaryPasswords.changeToken(made.id(), checked)).isNull();
        var current = db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(made.id())).fetchOne(USERS.PASSWORD_HASH);
        assertThat(temporaryPasswords.changeToken(made.id(), current)).isNotBlank();
    }

    @Test
    void everyOtherWayOfSettingAPasswordClearsTheTemporaryOne() throws Exception {
        var admin = login(ADMIN, DEV);
        // the reset link (비밀번호를 잊으셨나요?)
        var a = make(admin, email("reset"));
        var reset = tokens.issue(a.id(), "password_reset", Duration.ofHours(1), null);
        send(new MockHttpSession(), post("/api/v1/auth/password-reset/confirm"), "{\"token\":\"" + reset + "\",\"newPassword\":\"" + OWN + "\"}")
                .andExpect(status().isNoContent());
        assertThat(rows(a.id())).isZero();

        // my own change (계정 설정): a session from the change form, then a row put back as if one were issued meanwhile
        var bEmail = email("own");
        var b = make(admin, bEmail);
        var mine = new MockHttpSession();
        change(mine, changeToken(bEmail, b.password()), "first-own-2026!").andExpect(status().isOk());
        putRow(b.id());
        send(mine, put("/api/v1/me/password"), "{\"currentPassword\":\"first-own-2026!\",\"newPassword\":\"second-own-2026!\"}")
                .andExpect(status().isNoContent());
        assertThat(rows(b.id())).isZero();

        // an invitation accepted while a row exists (put in directly: issuing through the API would end the invitation)
        var dEmail = email("accept");
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"초대\",\"email\":\"" + dEmail + "\",\"isAdmin\":false}").andExpect(status().isCreated());
        var d = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(dEmail)).fetchOne(USERS.ID);
        putRow(d);
        var link = tokens.issue(d, "invitation", Duration.ofHours(1), null);
        send(new MockHttpSession(), post("/api/v1/auth/invitations/" + link + "/accept"), "{\"name\":\"초대\",\"password\":\"" + OWN + "\"}")
                .andExpect(status().isOk());
        assertThat(rows(d)).isZero();
    }
}

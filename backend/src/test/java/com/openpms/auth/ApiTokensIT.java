package com.openpms.auth;

import static com.openpms.db.Tables.API_TOKENS;
import static com.openpms.db.Tables.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.json.JsonMapper;

/** 외부 연동 API 토큰: issued once, act as their owner, read-only ones only read, never reach sign-in or tokens, revocable, switchable off. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ApiTokensIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        db.deleteFrom(API_TOKENS).execute();
        db.deleteFrom(SETTINGS).where(SETTINGS.KEY.eq("api.tokens_enabled")).execute();
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    String issue(MockHttpSession s, String scope) throws Exception {
        var res = mvc.perform(post("/api/v1/me/api-tokens").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"연동 시험\",\"scope\":\"" + scope + "\",\"expiresInDays\":30}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var node = json.readTree(res);
        assertThat(node.get("token").get("scope").asString()).isEqualTo(scope);
        return node.get("secret").asString();
    }

    @Test
    void aTokenActsAsItsOwnerWithinItsScope() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var read = issue(admin, "read");
        assertThat(read).startsWith("opms_");
        assertThat(db.fetchCount(API_TOKENS)).isEqualTo(1);
        // only the hash is kept
        assertThat(new String(db.select(API_TOKENS.TOKEN_HASH).from(API_TOKENS).fetchOne(API_TOKENS.TOKEN_HASH), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(read);

        mvc.perform(get("/api/v1/work-packages/3").header("Authorization", "Bearer " + read)).andExpect(status().isOk()).andExpect(jsonPath("$.number").value(3));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + read)).andExpect(jsonPath("$.email").value("seoyeon.kim@openpms.local"));
        mvc.perform(post("/api/v1/work-packages/query").header("Authorization", "Bearer " + read).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"web-platform\",\"page\":1,\"pageSize\":5,\"spec\":{\"v\":1,\"filters\":[],\"columns\":[\"subject\"],\"sort\":[],\"hierarchy\":false,\"sums\":false,\"includeSubprojects\":false}}"))
                .andExpect(status().isOk());
        var wp = json.readTree(mvc.perform(get("/api/v1/work-packages/3").header("Authorization", "Bearer " + read)).andReturn().getResponse().getContentAsString());
        var change = "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"subject\":\"" + wp.get("subject").asString() + "\"}";
        mvc.perform(patch("/api/v1/work-packages/3").header("Authorization", "Bearer " + read).contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("읽기 전용 토큰입니다."));
        // never sign-in, passwords or tokens
        mvc.perform(get("/api/v1/me/api-tokens").header("Authorization", "Bearer " + read)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + read)).andExpect(status().isForbidden());

        // a write token changes things without a session or a CSRF token
        var write = issue(admin, "write");
        mvc.perform(patch("/api/v1/work-packages/3").header("Authorization", "Bearer " + write).contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk());
        // not even an administrator's token reaches the integration settings
        mvc.perform(get("/api/v1/admin/webhooks").header("Authorization", "Bearer " + write)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/settings/api").header("Authorization", "Bearer " + write).contentType(MediaType.APPLICATION_JSON).content("{\"tokensEnabled\":true}"))
                .andExpect(status().isForbidden());
        // a member's token has the member's rights
        var member = issue(login("haneul.lee@openpms.local"), "write");
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + member)).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/work-packages/3").header("Authorization", "Bearer opms_nonsense")).andExpect(status().isUnauthorized());
        // revoked: no longer works
        var id = json.readTree(mvc.perform(get("/api/v1/me/api-tokens").session(admin)).andReturn().getResponse().getContentAsString()).get(0).get("id").asString();
        mvc.perform(delete("/api/v1/me/api-tokens/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/work-packages/3").header("Authorization", "Bearer " + write)).andExpect(status().isUnauthorized());

        // administrators see everyone's and can switch tokens off
        mvc.perform(get("/api/v1/admin/api-tokens").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(put("/api/v1/admin/settings/api").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"tokensEnabled\":false}"))
                .andExpect(jsonPath("$.tokensEnabled").value(false));
        mvc.perform(get("/api/v1/work-packages/3").header("Authorization", "Bearer " + read)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/api-tokens").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"scope\":\"read\"}")).andExpect(status().isForbidden());

        // the description is public
        mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.1.0"));
    }

    /** Spring decodes %61 to "a" before it routes, so a letter spelled in percent-encoding must not open a closed path. */
    @Test
    void aPercentEncodedLetterOpensNoClosedPath() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var write = issue(admin, "write");
        var refused = "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.";
        mvc.perform(get(new URI("/api/v1/me/%61pi-tokens")).header("Authorization", "Bearer " + write))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
        mvc.perform(post(new URI("/api/v1/me/%61pi-tokens")).header("Authorization", "Bearer " + write).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"토큰이 만든 토큰\",\"scope\":\"write\",\"expiresInDays\":30}")).andExpect(status().isForbidden());
        mvc.perform(get(new URI("/api/v1/admin/%77ebhooks")).header("Authorization", "Bearer " + write))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
        mvc.perform(put(new URI("/api/v1/me/p%61ssword")).header("Authorization", "Bearer " + write).contentType(MediaType.APPLICATION_JSON)
                // a wrong current password: even if the route were reached, nothing would change (422 instead of 403)
                .content("{\"currentPassword\":\"not-the-password\",\"newPassword\":\"changed-by-token-2026!\"}")).andExpect(status().isForbidden());
        assertThat(db.fetchCount(API_TOKENS, API_TOKENS.NAME.eq("토큰이 만든 토큰"))).isZero();
    }

    @Test
    void expiredTokensStopAdministratorsRevokeAnyoneSUseIsRecordedAndTheRateIsCapped() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var member = login("haneul.lee@openpms.local");
        var mine = issue(member, "read");
        var memberToken = json.readTree(mvc.perform(get("/api/v1/me/api-tokens").session(member)).andReturn().getResponse().getContentAsString()).get(0);
        var id = java.util.UUID.fromString(memberToken.get("id").asString());

        // use is recorded
        assertThat(db.select(API_TOKENS.LAST_USED_AT).from(API_TOKENS).where(API_TOKENS.ID.eq(id)).fetchOne(API_TOKENS.LAST_USED_AT)).isNull();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + mine)).andExpect(status().isOk());
        assertThat(db.select(API_TOKENS.LAST_USED_AT).from(API_TOKENS).where(API_TOKENS.ID.eq(id)).fetchOne(API_TOKENS.LAST_USED_AT)).isNotNull();
        // a read token may ask how much an export would hold
        mvc.perform(post("/api/v1/work-packages/export/count").header("Authorization", "Bearer " + mine).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"web-platform\",\"spec\":{\"v\":1,\"filters\":[],\"columns\":[\"subject\"],\"sort\":[],\"hierarchy\":false,\"sums\":false,\"includeSubprojects\":false}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limit").exists());

        // past its expiry date: 401
        db.update(API_TOKENS).set(API_TOKENS.EXPIRES_AT, java.time.OffsetDateTime.now().minusMinutes(1)).where(API_TOKENS.ID.eq(id)).execute();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + mine)).andExpect(status().isUnauthorized());
        db.update(API_TOKENS).set(API_TOKENS.EXPIRES_AT, java.time.OffsetDateTime.now().plusDays(1)).where(API_TOKENS.ID.eq(id)).execute();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + mine)).andExpect(status().isOk());

        // an administrator revokes someone else's token; a member cannot reach that route
        mvc.perform(delete("/api/v1/admin/api-tokens/" + id).session(member).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/api-tokens/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + mine)).andExpect(status().isUnauthorized());

        // 300 requests a minute per token, then 429 (the bucket refills as it goes, 5 a second, so a few more get through)
        var busy = issue(admin, "read");
        int ok = 0;
        int refused = 0;
        for (int i = 0; i < 400; i++) {
            int code = mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + busy)).andReturn().getResponse().getStatus();
            if (code == 200) ok++;
            else if (code == 429) refused++;
        }
        assertThat(ok).isBetween(300, 360);
        assertThat(ok + refused).isEqualTo(400);
    }
}

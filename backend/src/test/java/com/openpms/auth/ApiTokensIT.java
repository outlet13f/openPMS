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
}

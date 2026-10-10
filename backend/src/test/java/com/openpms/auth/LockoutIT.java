package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S35 관리 · 사용자: someone locked out for a while by failed sign-ins shows as such, is found by 잠김, and can be let in again. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class LockoutIT {
    static final String EMAIL = "doyoon.han@openpms.local";

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

    ResultActions login(MockHttpSession s, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    JsonNode find(MockHttpSession admin, String status) throws Exception {
        var r = mvc.perform(get("/api/v1/admin/users").param("status", status).session(admin)).andExpect(status().isOk()).andReturn();
        for (var u : json.readTree(r.getResponse().getContentAsString())) if (u.get("email").asString().equals(EMAIL)) return u;
        return null;
    }

    @Test
    void aTemporaryLockoutShowsInTheUserListUntilAnAdministratorLiftsIt() throws Exception {
        var admin = new MockHttpSession();
        login(admin, "seoyeon.kim@openpms.local", "openpms!").andExpect(status().isOk());
        try {
            for (int i = 0; i < 5; i++) login(new MockHttpSession(), EMAIL, "wrong-" + i).andExpect(status().isUnauthorized());
            login(new MockHttpSession(), EMAIL, "openpms!").andExpect(status().is(423));

            var listed = find(admin, "");
            assertThat(listed.get("status").asString()).isEqualTo("active");
            assertThat(listed.get("lockedUntil").isNull()).isFalse();
            assertThat(find(admin, "locked")).as("잠김 also finds a temporary lockout").isNotNull();
            assertThat(find(admin, "active")).as("still counted as active").isNotNull();

            var r = mvc.perform(patch("/api/v1/admin/users/" + listed.get("id").asString()).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"active\"}")).andExpect(status().isOk()).andReturn();
            assertThat(json.readTree(r.getResponse().getContentAsString()).get("lockedUntil").isNull()).isTrue();
            assertThat(find(admin, "locked")).isNull();
            login(new MockHttpSession(), EMAIL, "openpms!").andExpect(status().isOk());
        } finally {
            // other test classes sign in as this person
            db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL).where(USERS.EMAIL.eq(EMAIL)).execute();
        }
    }
}

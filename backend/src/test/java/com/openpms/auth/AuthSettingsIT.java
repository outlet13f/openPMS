package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 관리 · 로그인과 비밀번호: the rules reach every path that sets a password; lockout 0 is off; administrators only. */
@SpringBootTest(properties = {"openpms.jobs.enabled=false", "openpms.logins-per-minute=100"})
@ActiveProfiles("dev")
class AuthSettingsIT {
    static final String DEFAULTS = """
            {"minLength":10,"requiredClasses":[],"minClasses":0,"resetEnabled":true,"lockoutAttempts":5,"lockoutMinutes":15,
             "invitationTtlHours":168,"resetTtlMinutes":60}""";

    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    Tokens tokens;

    MockMvc mvc;
    MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = (MockHttpSession) login("seoyeon.kim@openpms.local", "openpms!").andExpect(status().isOk()).andReturn().getRequest().getSession();
    }

    @AfterEach
    void restore() throws Exception {
        save(DEFAULTS).andExpect(status().isOk());
    }

    ResultActions login(String email, String password) throws Exception {
        var session = new MockHttpSession();
        return mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    ResultActions save(String body) throws Exception {
        return mvc.perform(put("/api/v1/admin/settings/auth").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void onlyAdministratorsAndRangesAreChecked() throws Exception {
        var member = (MockHttpSession) login("junho.park@openpms.local", "openpms!").andReturn().getRequest().getSession();
        mvc.perform(get("/api/v1/admin/settings/auth").session(member)).andExpect(status().isForbidden());
        save(DEFAULTS.replace("\"minLength\":10", "\"minLength\":6")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("minLength"));
        save(DEFAULTS.replace("\"requiredClasses\":[]", "\"requiredClasses\":[\"digit\",\"upper\"]")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("minClasses"));
    }

    @Test
    void theRulesReachEveryPasswordAndThePublicPolicy() throws Exception {
        save(DEFAULTS.replace("\"minLength\":10", "\"minLength\":12").replace("\"requiredClasses\":[]", "\"requiredClasses\":[\"digit\"]")
                .replace("\"minClasses\":0", "\"minClasses\":1").replace("\"resetEnabled\":true", "\"resetEnabled\":false")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/password-policy")).andExpect(status().isOk()).andExpect(jsonPath("$.minLength").value(12))
                .andExpect(jsonPath("$.requiredClasses[0]").value("digit")).andExpect(jsonPath("$.resetEnabled").value(false));
        mvc.perform(post("/api/v1/auth/password-reset").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@y.z\"}"))
                .andExpect(status().isForbidden());
        // a reset link issued earlier still works, under the new rules
        var id = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("alex.kim@openpms.local")).fetchOne(USERS.ID);
        var hash = db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(id)).fetchOne(USERS.PASSWORD_HASH);
        var token = tokens.issue(id, "password_reset", Duration.ofHours(1), null);
        try {
            mvc.perform(post("/api/v1/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + token + "\",\"newPassword\":\"abcdefghijkl\"}")).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("비밀번호에 숫자을(를) 넣으세요."));
            mvc.perform(post("/api/v1/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + token + "\",\"newPassword\":\"abcdefghijk1\"}")).andExpect(status().isNoContent());
        } finally {
            db.update(USERS).set(USERS.PASSWORD_HASH, hash).where(USERS.ID.eq(id)).execute();
        }
        mvc.perform(put("/api/v1/me/password").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"openpms!\",\"newPassword\":\"short1\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void lockoutZeroNeverLocks() throws Exception {
        save(DEFAULTS.replace("\"lockoutAttempts\":5", "\"lockoutAttempts\":0")).andExpect(status().isOk());
        for (int i = 0; i < 7; i++) login("haneul.lee@openpms.local", "wrong").andExpect(status().isUnauthorized());
        login("haneul.lee@openpms.local", "openpms!").andExpect(status().isOk());
        save(DEFAULTS.replace("\"lockoutAttempts\":5", "\"lockoutAttempts\":2")).andExpect(status().isOk());
        try {
            for (int i = 0; i < 2; i++) login("haneul.lee@openpms.local", "wrong").andExpect(status().isUnauthorized());
            login("haneul.lee@openpms.local", "openpms!").andExpect(status().isLocked());
        } finally {
            db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL).where(USERS.EMAIL.eq("haneul.lee@openpms.local")).execute();
        }
    }
}

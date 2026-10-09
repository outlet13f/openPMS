package com.openpms;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Authorisation against the dev data set (scripts/dev.sh test runs this on a copy of the seed database):
 * a second user gets nothing back from a project they are not in, and the guards hold.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AccessIT {
    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MockHttpSession login(String email) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}"))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    void signedOutRequestsAre401ProblemJson() throws Exception {
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void writesWithoutCsrfTokenAreRefused() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isForbidden());
    }

    @Test
    void secondUserGetsNothingFromAProjectTheyAreNotIn() throws Exception {
        // 한도윤 is only in 모바일 앱 2.0; #3 belongs to 웹 플랫폼 개편 (private)
        var s = login("doyoon.han@openpms.local");
        mvc.perform(get("/api/v1/work-packages/3").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/web-platform").session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/web-platform/members").session(s)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/work-packages/query").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spec\":{\"v\":1,\"filters\":[],\"sort\":[],\"hierarchy\":false,\"includeSubprojects\":true},\"pageSize\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.wp.project.identifier == 'web-platform')]").isEmpty());
        mvc.perform(get("/api/v1/search/suggest").param("q", "결제").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void viewerCannotEdit() throws Exception {
        var s = login("minji.choi@openpms.local");
        mvc.perform(get("/api/v1/work-packages/3").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$._meta.allowedActions.edit").value(false));
        mvc.perform(patch("/api/v1/work-packages/3").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockVersion\":2,\"subject\":\"바꿔 보기\"}")).andExpect(status().isForbidden());
    }

    @Test
    void administrationIsForAdminsOnly() throws Exception {
        var s = login("junho.park@openpms.local");
        mvc.perform(get("/api/v1/admin/types").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void lockedAccountsCannotSignIn() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"former.staff@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().is(423));
    }
}

package com.openpms.system;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
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

/** 관리 · 시스템 정보: administrators see the version, database, migrations and backups; a backup without pg_dump says why. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class SystemIT {
    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    @Test
    void administratorsSeeTheSystemAndOthersDoNot() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        mvc.perform(get("/api/v1/admin/system").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.app.version").isNotEmpty())
                .andExpect(jsonPath("$.app.java").isNotEmpty())
                .andExpect(jsonPath("$.database.version").value(Matchers.startsWith("17")))
                .andExpect(jsonPath("$.database.sizeBytes").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.migrations.pending").value(0))
                .andExpect(jsonPath("$.migrations.applied").value(Matchers.greaterThanOrEqualTo(17)))
                .andExpect(jsonPath("$.migrations.recent.length()").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.backups.beforeUpgrade").value(false)) // the dev profile
                .andExpect(jsonPath("$.counts.projects").value(Matchers.greaterThan(0)));
        // the build container has no pg_dump: a backup on demand says so instead of pretending
        mvc.perform(post("/api/v1/admin/system/backup").session(admin).with(csrf())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("백업하지 못했습니다."));

        var member = login("haneul.lee@openpms.local");
        mvc.perform(get("/api/v1/admin/system").session(member)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/system/backup").session(member).with(csrf())).andExpect(status().isForbidden());
    }
}

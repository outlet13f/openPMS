package com.openpms.wp;

import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;

/** Copy, move and bulk (architecture.md §4.2 /copy /move /bulk). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class TransfersIT {
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

    MockHttpSession login() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return session;
    }

    ResultActions postJson(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post("/api/v1" + path).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String projectOf(long number) {
        return db.select(PROJECTS.IDENTIFIER).from(WORK_PACKAGES).join(PROJECTS).on(PROJECTS.ID.eq(WORK_PACKAGES.PROJECT_ID))
                .where(WORK_PACKAGES.NUMBER.eq(number)).fetchOne(PROJECTS.IDENTIFIER);
    }

    @Test
    void aBulkRequestIsAllOrNothing() throws Exception {
        var s = login();
        // #12 is a 기능, which 모바일 앱 does not use: #11 must not move either
        postJson(s, "/work-packages/bulk", "{\"numbers\":[11,12],\"action\":\"move\",\"targetProject\":\"mobile-app\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("#12"));
        assertThat(projectOf(11)).isEqualTo("web-platform");
        assertThat(projectOf(12)).isEqualTo("web-platform");
        postJson(s, "/work-packages/bulk", "{\"numbers\":[11],\"action\":\"update\",\"changes\":{\"subject\":\"x\"}}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void aMoveKeepsTheNumberClearsWhatBelongedToTheOldProjectAndIsJournaled() throws Exception {
        var s = login();
        try {
            postJson(s, "/work-packages/11/move", "{\"targetProject\":\"mobile-app\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.number").value(11)).andExpect(jsonPath("$.project.identifier").value("mobile-app"))
                    .andExpect(jsonPath("$.version").doesNotExist());
            var activities = mvc.perform(get("/api/v1/work-packages/11/activities").session(s)).andReturn().getResponse().getContentAsString();
            assertThat(activities).contains("\"label\":\"프로젝트\"").contains("웹 플랫폼 개편");
            postJson(s, "/work-packages/11/move", "{\"targetProject\":\"mobile-app\"}").andExpect(status().isUnprocessableContent());
        } finally {
            postJson(s, "/work-packages/11/move", "{\"targetProject\":\"web-platform\"}").andExpect(status().isOk());
        }
    }

    @Test
    void aCopyTakesTheSubtreeAndGoesThroughTheCreateRules() throws Exception {
        var s = login();
        var made = json.readTree(postJson(s, "/work-packages/7/copy", "{\"includeChildren\":true,\"subject\":\"접근성 개선 (사본)\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long n = made.get("number").asLong();
        var numbers = new ArrayList<Long>(java.util.List.of(n));
        var rel = json.readTree(mvc.perform(get("/api/v1/work-packages/" + n + "/relations").session(s)).andReturn().getResponse().getContentAsString());
        rel.get("children").forEach(c -> numbers.add(c.get("number").asLong()));
        assertThat(numbers).hasSize(4);
        assertThat(made.get("subject").asString()).isEqualTo("접근성 개선 (사본)");
        postJson(s, "/work-packages/bulk", "{\"numbers\":" + numbers + ",\"action\":\"delete\"}").andExpect(status().isOk()).andExpect(jsonPath("$.count").value(4));
        assertThat(db.fetchExists(WORK_PACKAGES, WORK_PACKAGES.NUMBER.in(numbers))).isFalse();
    }
}

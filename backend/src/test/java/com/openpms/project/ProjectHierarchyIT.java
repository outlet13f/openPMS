package com.openpms.project;

import static com.openpms.db.Tables.PROJECTS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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

/** S24 상위 프로젝트: a project is put under another one it may edit; never under itself or a project below it. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ProjectHierarchyIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        // seed: 백업 체계 개선 is under 사내 인프라
        db.update(PROJECTS).set(PROJECTS.PARENT_ID, id("infra")).where(PROJECTS.IDENTIFIER.eq("infra-backup")).execute();
        db.update(PROJECTS).setNull(PROJECTS.PARENT_ID).where(PROJECTS.IDENTIFIER.eq("web-platform")).execute();
        db.update(PROJECTS).set(PROJECTS.OVERVIEW_WIDGETS, new String[] {"description", "workSummary", "status", "members"}).where(PROJECTS.IDENTIFIER.eq("web-platform")).execute();
    }

    UUID id(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne(PROJECTS.ID);
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions move(MockHttpSession s, String project, UUID parent) throws Exception {
        return mvc.perform(patch("/api/v1/projects/" + project).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":" + (parent == null ? "null" : "\"" + parent + "\"") + "}"));
    }

    @Test
    void aProjectMovesUnderAnotherButNeverUnderItselfOrBelowIt() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        move(admin, "infra-backup", id("web-platform")).andExpect(status().isOk()).andExpect(jsonPath("$.parent.identifier").value("web-platform"));
        // 웹 플랫폼 is now above 백업 체계 개선: it cannot go under it, nor under itself
        move(admin, "web-platform", id("infra-backup")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("parent"));
        move(admin, "web-platform", id("web-platform")).andExpect(status().isUnprocessableContent());
        move(admin, "infra-backup", null).andExpect(status().isOk()).andExpect(jsonPath("$.parent").doesNotExist());

        // someone who manages 백업 체계 개선 but may not edit 모바일 앱 cannot put it there (seed: 이하늘 is not a member of 모바일 앱)
        var haneul = login("haneul.lee@openpms.local");
        move(haneul, "infra-backup", id("mobile-app")).andExpect(status().is4xxClientError());
    }

    /** S05 개요 위젯: known widgets only, in the order given; members without project.edit cannot change them. */
    @Test
    void theOverviewWidgetsAreKnownOnesInOrder() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        mvc.perform(patch("/api/v1/projects/web-platform").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"overviewWidgets\":[\"chart\"]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("overviewWidgets"));
        mvc.perform(patch("/api/v1/projects/web-platform").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"overviewWidgets\":[\"versions\",\"description\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.overviewWidgets[0]").value("versions")).andExpect(jsonPath("$.overviewWidgets[1]").value("description"));
        mvc.perform(patch("/api/v1/projects/web-platform").session(login("haneul.lee@openpms.local")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"overviewWidgets\":[]}")).andExpect(status().isForbidden());
    }
}

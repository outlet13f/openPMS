package com.openpms;

import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** S03 restore and delete (administrators), on a project made for the test. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ProjectLifecycleIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";

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

    MockHttpSession login(String email) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return session;
    }

    String postJson(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post("/api/v1" + path).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void deletingTakesTheItemsAndLeavesChildrenElsewhereWithoutAParent() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        postJson(admin, "/projects", "{\"name\":\"지울 프로젝트\",\"identifier\":\"to-delete\"}");
        var parent = json.readTree(postJson(admin, "/projects/to-delete/work-packages", "{\"typeId\":\"" + TASK + "\",\"subject\":\"지워질 상위\"}"));
        long parentNumber = parent.get("number").asLong();
        // a child in another project
        var child = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(5L)).fetchOne(WORK_PACKAGES.ID);
        var oldParent = db.select(WORK_PACKAGES.PARENT_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(child)).fetchOne(WORK_PACKAGES.PARENT_ID);
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.PARENT_ID, java.util.UUID.fromString(parent.get("id").asString())).where(WORK_PACKAGES.ID.eq(child)).execute();
        try {
            mvc.perform(delete("/api/v1/projects/to-delete").session(login("junho.park@openpms.local")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"confirmIdentifier\":\"to-delete\"}")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/projects/to-delete").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirmIdentifier\":\"to-delet\"}")).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("confirmIdentifier"));
            mvc.perform(delete("/api/v1/projects/to-delete").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirmIdentifier\":\"to-delete\"}")).andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/projects/to-delete").session(admin)).andExpect(status().isNotFound());
            assertThat(db.fetchExists(WORK_PACKAGES, WORK_PACKAGES.NUMBER.eq(parentNumber))).isFalse();
            assertThat(db.select(WORK_PACKAGES.PARENT_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(child)).fetchOne(WORK_PACKAGES.PARENT_ID)).isNull();
        } finally {
            db.update(WORK_PACKAGES).set(WORK_PACKAGES.PARENT_ID, oldParent).where(WORK_PACKAGES.ID.eq(child)).execute();
        }
    }

    @Test
    void aSubprojectIsRestoredAfterItsParent() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        postJson(admin, "/projects", "{\"name\":\"보관 상위\",\"identifier\":\"arch-parent\"}");
        var parentId = json.readTree(mvc.perform(get("/api/v1/projects/arch-parent").session(admin)).andReturn().getResponse().getContentAsString()).get("id").asString();
        postJson(admin, "/projects", "{\"name\":\"보관 하위\",\"identifier\":\"arch-child\",\"parentId\":\"" + parentId + "\"}");
        postJson(admin, "/projects/arch-child/archive", "{}");
        postJson(admin, "/projects/arch-parent/archive", "{}");
        mvc.perform(post("/api/v1/projects/arch-child/unarchive").session(login("junho.park@openpms.local")).with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/projects/arch-child/unarchive").session(admin).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/projects/arch-parent/unarchive").session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/projects/arch-child/unarchive").session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/projects/arch-parent").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmIdentifier\":\"arch-parent\"}")).andExpect(status().isConflict());
        for (var id : new String[] {"arch-child", "arch-parent"})
            mvc.perform(delete("/api/v1/projects/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirmIdentifier\":\"" + id + "\"}")).andExpect(status().isNoContent());
    }
}

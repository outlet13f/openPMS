package com.openpms.wp;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/**
 * Sharing one item with someone outside its project (한도윤 is not in 웹 플랫폼 개편): they get that item and
 * nothing else of the project, with only what the share role allows.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class SharingIT {
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

    String userId(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID).toString();
    }

    int lockVersion(MockHttpSession s, int number) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/work-packages/" + number).session(s)).andReturn().getResponse().getContentAsString()).get("lockVersion").asInt();
    }

    @Test
    void aShareGivesOneItemAndOnlyWhatItsRoleAllows() throws Exception {
        var manager = login("seoyeon.kim@openpms.local");
        var member = login("junho.park@openpms.local");
        var doyoon = userId("doyoon.han@openpms.local");

        // only wp.share may share; nobody shares with themselves
        mvc.perform(post("/api/v1/work-packages/11/shares").session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + doyoon + "\",\"role\":\"view\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/work-packages/11/shares").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId("seoyeon.kim@openpms.local") + "\",\"role\":\"view\"}")).andExpect(status().isUnprocessableContent());

        var outsider = login("doyoon.han@openpms.local");
        mvc.perform(get("/api/v1/work-packages/11").session(outsider)).andExpect(status().isNotFound());

        var created = mvc.perform(post("/api/v1/work-packages/11/shares").session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + doyoon + "\",\"role\":\"comment\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var shareId = json.readTree(created).get("id").asString();
        assertThat(db.fetchCount(NOTIFICATIONS, NOTIFICATIONS.REASON.eq("shared").and(NOTIFICATIONS.RECIPIENT_ID.eq(java.util.UUID.fromString(doyoon))))).isEqualTo(1);

        // the item, with its role; comment yes, edit no; the project's other items stay invisible
        mvc.perform(get("/api/v1/work-packages/11").session(outsider)).andExpect(status().isOk())
                .andExpect(jsonPath("$._meta.sharedWithMe").value("comment"))
                .andExpect(jsonPath("$._meta.allowedActions.edit").value(false))
                .andExpect(jsonPath("$._meta.allowedActions.comment").value(true));
        mvc.perform(post("/api/v1/work-packages/11/comments").session(outsider).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyHtml\":\"<p>외부 의견</p>\"}")).andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/work-packages/11").session(outsider).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockVersion\":" + lockVersion(outsider, 11) + ",\"subject\":\"바꿔 보기\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-packages/3").session(outsider)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/work-packages/query").session(outsider).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"web-platform\",\"spec\":{\"v\":1,\"filters\":[],\"sort\":[],\"hierarchy\":false,\"includeSubprojects\":true},\"pageSize\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].wp.number").value(11));

        // edit role: the subject can change
        mvc.perform(patch("/api/v1/shares/" + shareId).session(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"edit\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("edit"));
        mvc.perform(patch("/api/v1/work-packages/11").session(outsider).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockVersion\":" + lockVersion(outsider, 11) + ",\"subject\":\"외부에서 고친 제목\"}")).andExpect(status().isOk());

        // removed: gone again
        mvc.perform(delete("/api/v1/shares/" + shareId).session(manager).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/work-packages/11").session(outsider)).andExpect(status().isNotFound());
    }
}

package com.openpms.query;

import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S06 기준선: the listed items marked with what changed since a past point, and the values they had then. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class BaselineIT {
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
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode query(MockHttpSession s, String baseline) throws Exception {
        var spec = "{\"v\":1,\"filters\":[],\"columns\":[\"subject\",\"status\",\"priority\"],\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,"
                + "\"hierarchy\":false,\"sums\":false,\"includeSubprojects\":false,\"baseline\":" + baseline + "}";
        var res = send(s, post("/api/v1/work-packages/query"), "{\"projectId\":\"web-platform\",\"page\":1,\"pageSize\":100,\"spec\":" + spec + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    JsonNode row(JsonNode result, long number) {
        for (var r : result.get("rows")) if (r.get("wp").get("number").asLong() == number) return r;
        throw new AssertionError("no #" + number);
    }

    @Test
    void changesSinceTheBaselineAreMarkedWithTheValuesTheyHadThen() throws Exception {
        var s = login();
        var wp = json.readTree(send(s, get("/api/v1/work-packages/12"), "").andReturn().getResponse().getContentAsString());
        var before = wp.get("priority").get("name").asString();
        var high = db.select(com.openpms.db.Tables.PRIORITIES.ID).from(com.openpms.db.Tables.PRIORITIES).where(com.openpms.db.Tables.PRIORITIES.NAME.eq("긴급"))
                .fetchOne(com.openpms.db.Tables.PRIORITIES.ID);
        send(s, patch("/api/v1/work-packages/12"), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"priorityId\":\"" + high + "\"}").andExpect(status().isOk());
        var made = json.readTree(send(s, post("/api/v1/projects/web-platform/work-packages"), "{\"subject\":\"기준선 뒤에 만든 작업\",\"typeId\":\"" + wp.get("type").get("id").asString() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long number = made.get("number").asLong();
        try {
            var r = query(s, "{\"kind\":\"yesterday\"}");
            assertThat(r.get("baselineAt").isNull()).isFalse();
            assertThat(row(r, 12).get("baseline").get("added").asBoolean()).isFalse();
            assertThat(row(r, 12).get("baseline").get("changed").get("priority").asString()).isEqualTo(before);
            assertThat(row(r, number).get("baseline").get("added").asBoolean()).isTrue();
            assertThat(row(r, 2).get("baseline").isNull()).as("untouched since yesterday").isTrue();
            // no baseline, or one in the future: plain rows
            assertThat(query(s, "null").get("baselineAt").isNull()).isTrue();
            assertThat(query(s, "{\"kind\":\"date\",\"date\":\"2999-01-01\"}").get("baselineAt").isNull()).isTrue();
        } finally {
            send(s, delete("/api/v1/work-packages/" + number), "").andExpect(status().is2xxSuccessful());
            var now = db.select(WORK_PACKAGES.LOCK_VERSION).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(12L)).fetchOne(WORK_PACKAGES.LOCK_VERSION);
            send(s, patch("/api/v1/work-packages/12"), "{\"lockVersion\":" + now + ",\"priorityId\":\"" + wp.get("priority").get("id").asString() + "\"}").andExpect(status().isOk());
        }
    }
}

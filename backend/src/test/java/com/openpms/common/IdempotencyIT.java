package com.openpms.common;

import static com.openpms.db.Tables.IDEMPOTENCY_KEYS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.jobs.Housekeeping;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import tools.jackson.databind.json.JsonMapper;

/** Idempotency-Key: a retried create makes one work package and gets the first answer back; a reused key with another body is refused. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class IdempotencyIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;
    @Autowired
    IdempotencyFilter filter;
    @Autowired
    Housekeeping housekeeping;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(filter).build();
    }

    @AfterEach
    void cleanUp() {
        db.deleteFrom(IDEMPOTENCY_KEYS).execute();
    }

    MockHttpSession login() throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions create(MockHttpSession s, String key, String subject, String typeId) throws Exception {
        var r = post("/api/v1/projects/web-platform/work-packages").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"" + subject + "\",\"typeId\":\"" + typeId + "\"}");
        if (key != null) r = r.header(IdempotencyFilter.HEADER, key);
        return mvc.perform(r);
    }

    @Test
    void aRetriedCreateMakesOneWorkPackage() throws Exception {
        var s = login();
        var typeId = json.readTree(mvc.perform(get("/api/v1/work-packages/3").session(s)).andReturn().getResponse().getContentAsString()).get("type").get("id").asString();
        var before = db.fetchCount(WORK_PACKAGES);
        var first = create(s, "retry-1", "한 번만 만들어질 작업", typeId).andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        long number = json.readTree(first).get("number").asLong();
        try {
            // the same request again: the first answer, nothing new
            var again = create(s, "retry-1", "한 번만 만들어질 작업", typeId).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(again).get("number").asLong()).isEqualTo(number);
            assertThat(db.fetchCount(WORK_PACKAGES)).isEqualTo(before + 1);
            // the same key for something else: refused
            create(s, "retry-1", "다른 작업", typeId).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("같은 Idempotency-Key를 다른 요청에 썼습니다. 요청마다 새 키를 쓰세요."));
            // a refused request keeps nothing: fixed and sent again with its key, it runs
            create(s, "retry-2", "", typeId).andExpect(status().isUnprocessableContent());
            assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq("retry-2"))).isZero();
            // a key too long
            create(s, "k".repeat(256), "x", typeId).andExpect(status().isUnprocessableContent());
            // without a key nothing is kept
            var plain = json.readTree(create(s, null, "키 없는 작업", typeId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("number").asLong();
            mvc.perform(delete("/api/v1/work-packages/" + plain).session(s).with(csrf())).andExpect(status().is2xxSuccessful());
            assertThat(db.fetchCount(IDEMPOTENCY_KEYS)).isEqualTo(1);
            // kept for a day, then the daily clean-up removes it
            db.update(IDEMPOTENCY_KEYS).set(IDEMPOTENCY_KEYS.CREATED_AT, OffsetDateTime.now().minusHours(25)).execute();
            housekeeping.tokens(Instant.now());
            assertThat(db.fetchCount(IDEMPOTENCY_KEYS)).isZero();
        } finally {
            mvc.perform(delete("/api/v1/work-packages/" + number).session(s).with(csrf())).andExpect(status().is2xxSuccessful());
        }
    }
}

package com.openpms.notification;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S19 알림 필터: the server filters and counts over every notification, not just the newest 500 it lists. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class NotificationFiltersIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;

    MockMvc mvc;
    UUID me;
    /** the user's own notifications from the dev data: put back afterwards for the other tests */
    org.jooq.Result<com.openpms.db.tables.records.NotificationsRecord> saved;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        me = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("seoyeon.kim@openpms.local")).fetchOne(USERS.ID);
        saved = db.selectFrom(NOTIFICATIONS).where(NOTIFICATIONS.RECIPIENT_ID.eq(me)).fetch();
        db.deleteFrom(NOTIFICATIONS).where(NOTIFICATIONS.RECIPIENT_ID.eq(me)).execute();
    }

    @AfterEach
    void cleanUp() {
        db.deleteFrom(NOTIFICATIONS).where(NOTIFICATIONS.RECIPIENT_ID.eq(me)).execute();
        saved.forEach(r -> r.changed(true)); // fetched records insert as they were, every column
        db.batchInsert(saved).execute();
    }

    MockHttpSession login() throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    void notify(long number, String reason, OffsetDateTime at) {
        var wp = db.select(WORK_PACKAGES.ID, WORK_PACKAGES.PROJECT_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(number)).fetchOne();
        db.insertInto(NOTIFICATIONS).set(NOTIFICATIONS.RECIPIENT_ID, me).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp.value1()).set(NOTIFICATIONS.PROJECT_ID, wp.value2())
                .set(NOTIFICATIONS.REASON, reason).set(NOTIFICATIONS.CREATED_AT, at).execute();
    }

    JsonNode read(MockHttpSession s, String url) throws Exception {
        return json.readTree(mvc.perform(get(url).session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    int items(JsonNode groups) {
        int n = 0;
        for (var g : groups) n += g.get("items").size();
        return n;
    }

    @Test
    void filtersAndCountsReachPastTheNewest500() throws Exception {
        var now = OffsetDateTime.now();
        // an old mention in 모바일 앱 (#21 belongs there in the dev data), then 600 newer watched items in 웹 플랫폼
        var mobileItem = db.select(WORK_PACKAGES.NUMBER).from(WORK_PACKAGES).join(com.openpms.db.Tables.PROJECTS).on(com.openpms.db.Tables.PROJECTS.ID.eq(WORK_PACKAGES.PROJECT_ID))
                .where(com.openpms.db.Tables.PROJECTS.IDENTIFIER.eq("mobile-app")).limit(1).fetchOne(WORK_PACKAGES.NUMBER);
        notify(mobileItem, "mentioned", now.minusDays(10));
        for (int i = 0; i < 600; i++) notify(3, "watched", now.minusMinutes(i));
        var s = login();

        assertThat(items(read(s, "/api/v1/notifications"))).isEqualTo(500);
        // the old mention is not among the newest 500, but a filter finds it
        var mentions = read(s, "/api/v1/notifications?reasons=mentioned");
        assertThat(items(mentions)).isEqualTo(1);
        assertThat(mentions.get(0).get("workPackage").get("number").asLong()).isEqualTo(mobileItem);
        assertThat(items(read(s, "/api/v1/notifications?project=mobile-app"))).isEqualTo(1);
        assertThat(items(read(s, "/api/v1/notifications?reasons=watched&project=mobile-app"))).isZero();

        // counts over everything
        var c = read(s, "/api/v1/notifications/counts");
        assertThat(c.get("all").asInt()).isEqualTo(601);
        assertThat(c.get("byReason").get("watched").asInt()).isEqualTo(600);
        assertThat(c.get("byReason").get("mentioned").asInt()).isEqualTo(1);
        assertThat(c.get("allProjects").asInt()).isEqualTo(601);
        // within a project: per reason; within reasons: per project
        var inMobile = read(s, "/api/v1/notifications/counts?project=mobile-app");
        assertThat(inMobile.get("all").asInt()).isEqualTo(1);
        assertThat(inMobile.get("byReason").has("watched")).isFalse();
        var watched = read(s, "/api/v1/notifications/counts?reasons=watched");
        assertThat(watched.get("allProjects").asInt()).isEqualTo(600);
        assertThat(watched.get("projects").size()).isEqualTo(1);
        assertThat(watched.get("projects").get(0).get("identifier").asString()).isEqualTo("web-platform");

        // 모두 읽음 with a filter: everything it matches, listed or not, and nothing else
        mvc.perform(post("/api/v1/notifications/read").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reasons\":[\"watched\"]}"))
                .andExpect(status().isNoContent());
        assertThat(read(s, "/api/v1/notifications/unread-count").get("count").asInt()).isEqualTo(1);
        mvc.perform(post("/api/v1/notifications/read").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"project\":\"mobile-app\"}"))
                .andExpect(status().isNoContent());
        assertThat(read(s, "/api/v1/notifications/unread-count").get("count").asInt()).isZero();
    }
}

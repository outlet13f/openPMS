package com.openpms.notification;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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

/** S20: settings are checked, and fan-out follows them (박준호's items and others in 웹 플랫폼 개편). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class NotificationSettingsIT {
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

    long count(UUID recipient, long number, String reason) {
        var wp = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(number)).fetchOne(WORK_PACKAGES.ID);
        return db.fetchCount(NOTIFICATIONS, NOTIFICATIONS.RECIPIENT_ID.eq(recipient).and(NOTIFICATIONS.WORK_PACKAGE_ID.eq(wp)).and(NOTIFICATIONS.REASON.eq(reason)));
    }

    int lockVersion(MockHttpSession s, int number) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/work-packages/" + number).session(s)).andReturn().getResponse().getContentAsString()).get("lockVersion").asInt();
    }

    @Test
    void preferencesAndSettingsAreChecked() throws Exception {
        var s = login("haneul.lee@openpms.local");
        mvc.perform(put("/api/v1/me/preferences").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"digestTimes\":[\"08:00\",\"09:00\",\"10:00\",\"11:00\",\"12:00\"]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("digestTimes"));
        mvc.perform(put("/api/v1/me/preferences").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"digestTimes\":[\"18:30\",\"08:00\"],\"immediateOnMention\":false,\"remindersPausedUntil\":\"2030-01-31\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.digestTimes[0]").value("08:00")).andExpect(jsonPath("$.immediateOnMention").value(false));
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(jsonPath("$.preferences.remindersPausedUntil").value("2030-01-31"));
        mvc.perform(put("/api/v1/me/notification-settings/default").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startDateAlertDays\":2}")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/me/notification-settings").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.default.overdueAlert").value(false)).andExpect(jsonPath("$.default.dueDateAlertDays").value(1));
    }

    @Test
    void fanOutFollowsTheSettings() throws Exception {
        var junho = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("junho.park@openpms.local")).fetchOne(USERS.ID);
        var j = login("junho.park@openpms.local");
        // 박준호: not as assignee; every comment and priority change in the project, but only for 웹 플랫폼 개편
        mvc.perform(put("/api/v1/me/notification-settings/default").session(j).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee\":false}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/me/notification-settings/projects/web-platform").session(j).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee\":false,\"commented\":true}")).andExpect(status().isOk());

        var seoyeon = login("seoyeon.kim@openpms.local");
        long assignedBefore = count(junho, 4, "assigned");
        mvc.perform(patch("/api/v1/work-packages/4").session(seoyeon).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockVersion\":" + lockVersion(seoyeon, 4) + ",\"subject\":\"결제 API 연동 (2차)\"}")).andExpect(status().isOk());
        assertThat(count(junho, 4, "assigned")).isEqualTo(assignedBefore); // his item, but assignee notifications are off

        mvc.perform(post("/api/v1/work-packages/12/comments").session(seoyeon).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyHtml\":\"<p>범위 확인</p>\"}")).andExpect(status().isCreated());
        assertThat(count(junho, 12, "commented")).isEqualTo(1); // not involved in #12, but asked for every comment here
    }
}

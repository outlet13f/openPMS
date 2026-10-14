package com.openpms.itsm;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class SlaJobsIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired SlaJobs jobs;
    ItsmFixture f;
    MockHttpSession admin, agent;
    String desk;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        desk = f.newDesk(admin);
    }

    UUID urgentIncidentAssignedToJunho() throws Exception {
        var n = f.body(f.send(agent, post("/api/v1/projects/" + desk + "/work-packages"), "{\"subject\":\"서버 다운\",\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"priorityId\":\"" + ItsmFixture.URGENT + "\",\"assigneeId\":\"" + ItsmFixture.JUNHO + "\",\"requesterId\":\"" + ItsmFixture.MINJI + "\"}")
                .andExpect(status().isCreated()).andReturn()).get("number").asLong();
        return db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne(WORK_PACKAGES.ID);
    }

    int notifications(UUID wp, String reason) {
        return db.fetchCount(NOTIFICATIONS, NOTIFICATIONS.WORK_PACKAGE_ID.eq(wp), NOTIFICATIONS.REASON.eq(reason), NOTIFICATIONS.RECIPIENT_ID.eq(ItsmFixture.JUNHO));
    }

    @Test void aBreachIsMarkedAndAnnouncedOnce() throws Exception {
        var wp = urgentIncidentAssignedToJunho();
        var created = db.select(SERVICE_TICKETS.CREATED_AT).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.CREATED_AT);
        var later = created.plusMinutes(16).toInstant(); // response target 15 min (24x7)
        jobs.run(later);
        jobs.run(later.plusSeconds(60));
        assertThat(db.select(SERVICE_TICKETS.RESPONSE_BREACHED).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.RESPONSE_BREACHED)).isTrue();
        assertThat(notifications(wp, "sla_breached")).isEqualTo(1);
    }

    @Test void aWarningComesWhenTwentyPercentIsLeft() throws Exception {
        var wp = urgentIncidentAssignedToJunho();
        var created = db.select(SERVICE_TICKETS.CREATED_AT).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.CREATED_AT);
        jobs.run(created.plusMinutes(10).toInstant());   // 5 of 15 min left: 33 %, nothing
        assertThat(notifications(wp, "sla_warning")).isZero();
        jobs.run(created.plusMinutes(12).plusSeconds(30).toInstant()); // 2.5 of 15 min left
        jobs.run(created.plusMinutes(13).toInstant());
        assertThat(notifications(wp, "sla_warning")).isEqualTo(1);
    }

    @Test void pausedAndResolvedTicketsAreLeftAlone() throws Exception {
        var wp = urgentIncidentAssignedToJunho();
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.PAUSED_SINCE, OffsetDateTime.now()).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).execute();
        jobs.run(Instant.now().plusSeconds(3600 * 5));
        assertThat(notifications(wp, "sla_breached")).isZero();
    }

    @Test void aFirstResponseStopsTheResponseClock() throws Exception {
        var wp = urgentIncidentAssignedToJunho();
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.FIRST_RESPONDED_AT, OffsetDateTime.now()).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).execute();
        var created = db.select(SERVICE_TICKETS.CREATED_AT).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.CREATED_AT);
        jobs.run(created.plusMinutes(20).toInstant());
        assertThat(db.select(SERVICE_TICKETS.RESPONSE_BREACHED).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.RESPONSE_BREACHED)).isFalse();
    }

    @Test void aDeadlineMovedLaterIsNotBreached() throws Exception {
        var wp = urgentIncidentAssignedToJunho();
        var created = db.select(SERVICE_TICKETS.CREATED_AT).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.CREATED_AT);
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.RESOLVE_DUE_AT, created.plusDays(10)).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).execute();
        jobs.run(created.plusMinutes(121).toInstant()); // past the old 120 min deadline, well before the new one
        assertThat(db.select(SERVICE_TICKETS.RESOLVE_BREACHED).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.RESOLVE_BREACHED)).isFalse();
    }
}

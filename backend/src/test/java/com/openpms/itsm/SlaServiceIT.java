package com.openpms.itsm;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
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
class SlaServiceIT {
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

    long incident(String extra) throws Exception {
        var r = f.body(f.send(agent, post("/api/v1/projects/" + desk + "/work-packages"), "{\"subject\":\"메일이 안 됩니다\",\"typeId\":\""
                + ItsmIds.TYPE_INCIDENT + "\",\"requesterId\":\"" + ItsmFixture.MINJI + "\"" + extra + "}").andExpect(status().isCreated()).andReturn());
        return r.get("number").asLong();
    }

    org.jooq.Record ticket(long number) {
        return db.select(SERVICE_TICKETS.fields()).from(SERVICE_TICKETS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(SERVICE_TICKETS.WORK_PACKAGE_ID))
                .where(WORK_PACKAGES.NUMBER.eq(number)).fetchOne();
    }

    int lockVersion(long number) throws Exception {
        return f.body(f.send(agent, get("/api/v1/work-packages/" + number), "").andReturn()).get("lockVersion").asInt();
    }

    void setStatus(long number, java.util.UUID statusId) throws Exception {
        f.send(agent, patch("/api/v1/work-packages/" + number), "{\"lockVersion\":" + lockVersion(number) + ",\"statusId\":\"" + statusId + "\"}")
                .andExpect(status().isOk());
    }

    @Test void aNewIncidentIsReceivedWithMatrixPriorityAndDeadlines() throws Exception {
        var n = incident(",\"impact\":1,\"urgency\":1");
        f.send(agent, get("/api/v1/work-packages/" + n), "")
                .andExpect(jsonPath("$.status.id").value(ItsmIds.STATUS_RECEIVED.toString()))
                .andExpect(jsonPath("$.priority.id").value(ItsmFixture.URGENT.toString()))
                .andExpect(jsonPath("$.serviceTicket.requester.id").value(ItsmFixture.MINJI.toString()))
                .andExpect(jsonPath("$.serviceTicket.channel").value("agent"));
        var t = ticket(n);
        var created = t.get(SERVICE_TICKETS.CREATED_AT);
        // 긴급 = 24x7: 15 min / 120 min
        assertThat(Duration.between(created, t.get(SERVICE_TICKETS.FIRST_RESPONSE_DUE_AT))).isEqualTo(Duration.ofMinutes(15));
        assertThat(Duration.between(created, t.get(SERVICE_TICKETS.RESOLVE_DUE_AT))).isEqualTo(Duration.ofMinutes(120));
    }

    @Test void anAgentCommentIsTheFirstResponseButAnInternalOneIsNot() throws Exception {
        var n = incident("");
        f.send(admin, patch("/api/v1/projects/" + desk), "{\"internalComments\":true}").andExpect(status().isOk());
        f.send(admin, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>내부 메모</p>\",\"internal\":true}").andExpect(status().is2xxSuccessful());
        assertThat(ticket(n).get(SERVICE_TICKETS.FIRST_RESPONDED_AT)).isNull();
        f.send(agent, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>확인 중입니다</p>\"}").andExpect(status().is2xxSuccessful());
        assertThat(ticket(n).get(SERVICE_TICKETS.FIRST_RESPONDED_AT)).isNotNull();
    }

    @Test void waitingForTheRequesterPausesTheResolveClock() throws Exception {
        var n = incident(",\"priorityId\":\"" + ItsmFixture.URGENT + "\"");
        var due = ticket(n).get(SERVICE_TICKETS.RESOLVE_DUE_AT);
        setStatus(n, ItsmIds.STATUS_WAITING_REQUESTER);
        assertThat(ticket(n).get(SERVICE_TICKETS.PAUSED_SINCE)).isNotNull();
        // pretend the pause began 30 minutes ago
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.PAUSED_SINCE, OffsetDateTime.now().minusMinutes(30))
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(ticket(n).get(SERVICE_TICKETS.WORK_PACKAGE_ID))).execute();
        setStatus(n, ItsmIds.STATUS_HANDLING);
        var t = ticket(n);
        assertThat(t.get(SERVICE_TICKETS.PAUSED_SINCE)).isNull();
        assertThat(t.get(SERVICE_TICKETS.PAUSED_TOTAL_SECONDS)).isBetween(1795L, 1810L);
        assertThat(Duration.between(due, t.get(SERVICE_TICKETS.RESOLVE_DUE_AT)).toSeconds()).isBetween(1795L, 1810L);
    }

    @Test void changingPriorityKeepsThePausesAndTheBreach() throws Exception {
        var n = incident(",\"priorityId\":\"" + ItsmFixture.URGENT + "\"");
        var id = ticket(n).get(SERVICE_TICKETS.WORK_PACKAGE_ID);
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.PAUSED_TOTAL_SECONDS, 600L).set(SERVICE_TICKETS.RESOLVE_BREACHED, true)
                .set(SERVICE_TICKETS.RESOLVE_WARNED, true).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(id)).execute();
        f.send(agent, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lockVersion(n) + ",\"priorityId\":\"" + ItsmFixture.NORMAL + "\"}")
                .andExpect(status().isOk());
        var t = ticket(n);
        assertThat(t.get(SERVICE_TICKETS.RESOLVE_BREACHED)).isTrue();
        assertThat(t.get(SERVICE_TICKETS.SLA_POLICY_ID)).isNotNull();
        // 보통 = business hours, 240 min + 10 min paused: later than 24x7 120 min, so the warning may come again
        assertThat(t.get(SERVICE_TICKETS.RESOLVE_WARNED)).isFalse();
        assertThat(t.get(SERVICE_TICKETS.RESOLVE_DUE_AT)).isAfter(t.get(SERVICE_TICKETS.CREATED_AT).plusMinutes(250).minusSeconds(1));
    }

    @Test void resolvingAndReopening() throws Exception {
        var n = incident("");
        setStatus(n, ItsmIds.STATUS_RESOLVED);
        assertThat(ticket(n).get(SERVICE_TICKETS.RESOLVED_AT)).isNotNull();
        setStatus(n, ItsmIds.STATUS_HANDLING);
        assertThat(ticket(n).get(SERVICE_TICKETS.RESOLVED_AT)).isNull();
    }

    @Test void withoutAPolicyThereAreNoDeadlines() throws Exception {
        var r = f.body(f.send(agent, post("/api/v1/projects/" + desk + "/work-packages"), "{\"subject\":\"모니터 요청\",\"typeId\":\""
                + ItsmIds.TYPE_SERVICE_REQUEST + "\",\"requesterId\":\"" + ItsmFixture.MINJI + "\"}").andExpect(status().isCreated()).andReturn());
        var t = ticket(r.get("number").asLong());
        assertThat(t.get(SERVICE_TICKETS.SLA_POLICY_ID)).isNull();
        assertThat(t.get(SERVICE_TICKETS.RESOLVE_DUE_AT)).isNull();
    }

    @Test void aTicketCannotBecomeAnOrdinaryWorkPackage() throws Exception {
        var ordinary = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
        // the ordinary type 작업 must be enabled in the desk, so the 422 comes from the ticket <-> ordinary rule
        f.send(admin, put("/api/v1/projects/" + desk + "/types"),
                "{\"typeIds\":[\"" + ItsmIds.TYPE_INCIDENT + "\",\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\",\"" + ordinary + "\"]}").andExpect(status().isOk());
        var n = incident("");
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lockVersion(n) + ",\"typeId\":\"" + ordinary + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("티켓 유형")));
    }

    @Test void agentsEditTheTicketFieldsAndItIsJournaled() throws Exception {
        var n = incident("");
        f.send(agent, patch("/api/v1/work-packages/" + n + "/service-ticket"), "{\"requesterId\":\"" + ItsmFixture.DOYOON + "\",\"impact\":1,\"urgency\":1,\"applyMatrix\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceTicket.requester.id").value(ItsmFixture.DOYOON.toString()))
                .andExpect(jsonPath("$.priority.id").value(ItsmFixture.URGENT.toString()));
        f.send(agent, get("/api/v1/work-packages/" + n + "/activities"), "")
                .andExpect(jsonPath("$[*].details[*].field", org.hamcrest.Matchers.hasItem("requesterId")));
        var requester = f.login(ItsmFixture.REQUESTER);
        f.send(requester, patch("/api/v1/work-packages/" + n + "/service-ticket"), "{\"impact\":3}").andExpect(status().isNotFound());
    }

    @Test void aTicketMadeInAPauseStatusIsPausedFromTheStart() throws Exception {
        var n = incident(",\"statusId\":\"" + ItsmIds.STATUS_WAITING_REQUESTER + "\"");
        var t = ticket(n);
        assertThat(t.get(SERVICE_TICKETS.PAUSED_SINCE)).isEqualTo(t.get(SERVICE_TICKETS.CREATED_AT));
        assertThat(t.get(SERVICE_TICKETS.RESOLVED_AT)).isNull();
        var closed = ticket(incident(",\"statusId\":\"" + ItsmIds.STATUS_RESOLVED + "\""));
        assertThat(closed.get(SERVICE_TICKETS.RESOLVED_AT)).isEqualTo(closed.get(SERVICE_TICKETS.CREATED_AT));
        assertThat(closed.get(SERVICE_TICKETS.PAUSED_SINCE)).isNull();
    }

    @Test void aCopyOfAResolvedTicketIsResolvedKeepsTheRequesterAndRaisesNoAlarm() throws Exception {
        var n = incident(",\"impact\":1,\"urgency\":1"); // 긴급, 24x7: 15 / 120 min
        f.send(agent, patch("/api/v1/work-packages/" + n + "/service-ticket"), "{\"assignmentGroupId\":\"25629a29-9f0b-50a1-a812-f04018678b83\"}")
                .andExpect(status().isOk());
        setStatus(n, ItsmIds.STATUS_RESOLVED);
        var copy = f.body(f.send(agent, post("/api/v1/work-packages/" + n + "/copy"), "{}").andExpect(status().is2xxSuccessful()).andReturn())
                .get("number").asLong();
        f.send(agent, get("/api/v1/work-packages/" + copy), "")
                .andExpect(jsonPath("$.serviceTicket.requester.id").value(ItsmFixture.MINJI.toString()))
                .andExpect(jsonPath("$.serviceTicket.impact").value(1))
                .andExpect(jsonPath("$.serviceTicket.assignmentGroup.id").value("25629a29-9f0b-50a1-a812-f04018678b83"));
        var t = ticket(copy);
        assertThat(t.get(SERVICE_TICKETS.RESOLVED_AT)).isNotNull();
        assertThat(t.get(SERVICE_TICKETS.REQUESTER_ID)).isEqualTo(ItsmFixture.MINJI);
        assertThat(t.get(SERVICE_TICKETS.URGENCY)).isEqualTo((short) 1);
        // no receipt: the requester is told about the copy as it stands
        assertThat(t.get(SERVICE_TICKETS.REQUESTER_MAILED_AT)).isNotNull();
        assertThat(t.get(SERVICE_TICKETS.REQUESTER_MAILED_STATUS_ID)).isEqualTo(ItsmIds.STATUS_RESOLVED);
        var id = t.get(SERVICE_TICKETS.WORK_PACKAGE_ID);
        jobs.run(java.time.Instant.now().plus(Duration.ofHours(5)));
        assertThat(db.fetchCount(NOTIFICATIONS, NOTIFICATIONS.WORK_PACKAGE_ID.eq(id), NOTIFICATIONS.REASON.in("sla_warning", "sla_breached"))).isZero();
    }

}

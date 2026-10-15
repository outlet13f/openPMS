package com.openpms.itsm;

import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Ticket filters (requester, channel, SLA breach), the SLA-deadline sort and the export columns. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ServiceDeskQueryIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    ItsmFixture f;
    MockHttpSession admin, agent;
    String desk;
    long minjis, doyoons;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        desk = f.newDesk(admin);
        minjis = make(ItsmFixture.MINJI);
        doyoons = make(ItsmFixture.DOYOON);
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.RESOLVE_BREACHED, true)
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(doyoons)))).execute();
    }

    long make(UUID requester) throws Exception {
        return f.body(f.send(agent, post("/api/v1/projects/" + desk + "/work-packages"), "{\"subject\":\"티켓\",\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"requesterId\":\"" + requester + "\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    ResultActions list(String filters, String sort) throws Exception {
        var spec = "{\"filters\":" + filters + ",\"sort\":" + sort + ",\"columns\":[\"subject\",\"requester\",\"slaDue\",\"slaState\"],\"includeSubprojects\":false}";
        return f.send(agent, post("/api/v1/work-packages/query"), "{\"projectId\":\"" + desk + "\",\"page\":1,\"pageSize\":50,\"spec\":" + spec + "}");
    }

    @Test void filterByRequesterChannelAndBreach() throws Exception {
        list("[{\"field\":\"requester\",\"op\":\"eq\",\"values\":[\"" + ItsmFixture.MINJI + "\"]}]", "[]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) minjis)));
        list("[{\"field\":\"requester\",\"op\":\"neq\",\"values\":[\"" + ItsmFixture.MINJI + "\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) doyoons)));
        list("[{\"field\":\"slaBreached\",\"op\":\"eq\",\"values\":[\"true\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) doyoons)));
        list("[{\"field\":\"slaBreached\",\"op\":\"eq\",\"values\":[\"false\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) minjis)));
        list("[{\"field\":\"channel\",\"op\":\"eq\",\"values\":[\"agent\"]}]", "[]")
                .andExpect(jsonPath("$.total").value(2));
        list("[{\"field\":\"channel\",\"op\":\"eq\",\"values\":[\"portal\"]}]", "[]")
                .andExpect(jsonPath("$.total").value(0));
        list("[{\"field\":\"assignmentGroup\",\"op\":\"eq\",\"values\":[\"" + UUID.randomUUID() + "\"]}]", "[]")
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test void sortBySlaDue() throws Exception {
        // the later-numbered ticket gets the earlier deadline, so a number sort would give the opposite order
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.RESOLVE_DUE_AT, java.time.OffsetDateTime.now().plusDays(30))
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(minjis)))).execute();
        list("[]", "[{\"field\":\"slaDue\",\"dir\":\"asc\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].wp.number").value((int) doyoons));
        list("[]", "[{\"field\":\"slaDue\",\"dir\":\"desc\"}]")
                .andExpect(jsonPath("$.rows[0].wp.number").value((int) minjis));
    }

    @Test void exportHasRequesterAndSlaColumns() throws Exception {
        var spec = "{\"filters\":[],\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"columns\":[\"subject\"],\"includeSubprojects\":false}";
        var csv = f.send(agent, post("/api/v1/work-packages/export"), "{\"projectId\":\"" + desk + "\",\"spec\":" + spec
                + ",\"format\":\"csv\",\"columns\":[\"number\",\"requester\",\"assignmentGroup\",\"slaDue\",\"slaState\"],\"title\":\"t\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("번호,요청자,처리 그룹,해결 기한,SLA");
        var lines = csv.split("\r\n");
        assertThat(lines[1]).startsWith(minjis + ",최민지,,20").endsWith(",정상");
        assertThat(lines[2]).startsWith(doyoons + ",한도윤,,20").endsWith(",위반");
    }
    /** A portal request for a catalog item by the given requester; returns the ticket number. */
    long request(String who, String item) throws Exception {
        return f.body(f.send(f.login(who), post("/api/v1/portal/desks/" + desk + "/tickets"),
                "{\"catalogItemId\":\"" + item + "\",\"subject\":\"카탈로그 요청\",\"descriptionHtml\":\"<p>부탁합니다</p>\"}")
                .andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    void approval(long number, String decision, boolean reminded) {
        var alex = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("alex.kim@openpms.local")).fetchOne(USERS.ID);
        db.insertInto(TICKET_APPROVALS).set(TICKET_APPROVALS.WORK_PACKAGE_ID, db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(number)))
                .set(TICKET_APPROVALS.STEP_NO, (short) 1).set(TICKET_APPROVALS.APPROVER_KIND, "requester_manager").set(TICKET_APPROVALS.APPROVER_ID, alex)
                .set(TICKET_APPROVALS.DECISION, decision).set(TICKET_APPROVALS.REMINDED_AT, reminded ? java.time.OffsetDateTime.now() : null).execute();
    }

    @Test void filterByCatalogItemAndApprovalState() throws Exception {
        var itemA = f.newItem(admin, desk, "항목 가", "[]");
        var itemB = f.newItem(admin, desk, "항목 나", "[]");
        long a = request(ItsmFixture.REQUESTER, itemA), b = request(ItsmFixture.OTHER_REQUESTER, itemB);
        approval(a, "pending", true);
        approval(b, "rejected", false);
        list("[{\"field\":\"catalogItem\",\"op\":\"eq\",\"values\":[\"" + itemA + "\"]}]", "[]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) a)))
                .andExpect(jsonPath("$.rows[0].wp.serviceTicket.catalogItem.name").value("항목 가"));
        list("[{\"field\":\"catalogItem\",\"op\":\"neq\",\"values\":[\"" + itemA + "\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.containsInAnyOrder((int) minjis, (int) doyoons, (int) b)));
        list("[]", "[]").andExpect(jsonPath("$.rows[?(@.wp.number == " + minjis + ")].wp.serviceTicket.catalogItem", Matchers.contains((Object) null)));
        list("[{\"field\":\"approvalState\",\"op\":\"eq\",\"values\":[\"pending\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) a)));
        list("[{\"field\":\"approvalState\",\"op\":\"eq\",\"values\":[\"delayed\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) a)));
        list("[{\"field\":\"approvalState\",\"op\":\"eq\",\"values\":[\"rejected\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.contains((int) b)));
        list("[{\"field\":\"approvalState\",\"op\":\"eq\",\"values\":[\"none\"]}]", "[]")
                .andExpect(jsonPath("$.rows[*].wp.number", Matchers.containsInAnyOrder((int) minjis, (int) doyoons)));
        list("[{\"field\":\"approvalState\",\"op\":\"eq\",\"values\":[\"bogus\"]}]", "[]").andExpect(status().isUnprocessableContent());
        var spec = "{\"filters\":[],\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"columns\":[\"subject\"],\"includeSubprojects\":false}";
        var csv = f.send(agent, post("/api/v1/work-packages/export"), "{\"projectId\":\"" + desk + "\",\"spec\":" + spec
                + ",\"format\":\"csv\",\"columns\":[\"number\",\"catalogItem\"],\"title\":\"t\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("번호,카탈로그 항목");
        var lines = csv.split("\r\n");
        assertThat(lines[1]).isEqualTo(minjis + ",");
        assertThat(lines[3]).isEqualTo(a + ",항목 가");
    }
}

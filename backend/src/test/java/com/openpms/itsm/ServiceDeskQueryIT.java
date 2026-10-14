package com.openpms.itsm;

import static com.openpms.db.Tables.SERVICE_TICKETS;
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
}

package com.openpms.itsm;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.hamcrest.Matchers;
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
class PortalFlowIT {
    @Autowired WebApplicationContext context;
    ItsmFixture f;
    MockHttpSession admin, agent, requester;
    String desk;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        desk = f.newDesk(admin);
    }

    long submit() throws Exception {
        var r = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"VPN이 끊깁니다\",\"descriptionHtml\":\"<p>아침부터</p>\",\"impact\":1,\"urgency\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status.name").value("접수"))
                .andReturn());
        return r.get("number").asLong();
    }

    @Test void anEmployeeSeesTheDeskAndSubmitsATicket() throws Exception {
        f.send(requester, get("/api/v1/portal/desks"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].identifier", Matchers.hasItem(desk)));
        var n = submit();
        f.send(agent, get("/api/v1/work-packages/" + n), "")
                .andExpect(jsonPath("$.serviceTicket.channel").value("portal"))
                .andExpect(jsonPath("$.serviceTicket.requester.id").value(ItsmFixture.MINJI.toString()))
                .andExpect(jsonPath("$.priority.id").value(ItsmFixture.URGENT.toString()));
        f.send(requester, get("/api/v1/portal/tickets?state=open"), "")
                .andExpect(jsonPath("$[0].number").value(n))
                .andExpect(jsonPath("$[0].hasNews").value(false));
    }

    @Test void anAgentAnswerIsNewsUntilTheRequesterOpensIt() throws Exception {
        var n = submit();
        f.send(agent, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>재접속해 보세요</p>\"}").andExpect(status().is2xxSuccessful());
        f.send(requester, get("/api/v1/portal/tickets"), "").andExpect(jsonPath("$[?(@.number == " + n + ")].hasNews").value(Matchers.contains(true)));
        f.send(requester, get("/api/v1/portal/tickets/" + n), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity[*].notesHtml", Matchers.hasItem("<p>재접속해 보세요</p>")))
                .andExpect(jsonPath("$.activity[?(@.notesHtml == '<p>재접속해 보세요</p>')].fromMe").value(Matchers.contains(false)));
        f.send(requester, get("/api/v1/portal/tickets"), "").andExpect(jsonPath("$[?(@.number == " + n + ")].hasNews").value(Matchers.contains(false)));
    }

    @Test void answeringAWaitingTicketMovesItBackToHandling() throws Exception {
        var n = submit();
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_WAITING_REQUESTER + "\"}")
                .andExpect(status().isOk());
        f.send(requester, post("/api/v1/portal/tickets/" + n + "/comments"), "{\"bodyHtml\":\"<p>다시 해도 안 됩니다</p>\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.id").value(ItsmIds.STATUS_HANDLING.toString()))
                .andExpect(jsonPath("$.activity[*].notesHtml", Matchers.hasItem("<p>다시 해도 안 됩니다</p>")));
    }

    @Test void archivedOrOrdinaryProjectsAreNotDesks() throws Exception {
        f.send(requester, get("/api/v1/portal/desks"), "").andExpect(jsonPath("$[*].identifier", Matchers.not(Matchers.hasItem("web-platform"))));
        var id = f.body(f.send(admin, get("/api/v1/projects/" + desk), "").andReturn()).get("id").asString();
        f.send(admin, post("/api/v1/projects/" + id + "/archive"), "").andExpect(status().is2xxSuccessful());
        f.send(requester, get("/api/v1/portal/desks"), "").andExpect(jsonPath("$[*].identifier", Matchers.not(Matchers.hasItem(desk))));
        f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"subject\":\"x\"}")
                .andExpect(status().isNotFound());
    }

    @Test void onlyTicketTypesAndPortalFieldsAreAccepted() throws Exception {
        f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc\",\"subject\":\"x\"}")
                .andExpect(status().isUnprocessableContent());
        // keys the portal does not offer are ignored, not applied
        var r = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"담당자 지정 시도\",\"assigneeId\":\"" + ItsmFixture.JUNHO + "\",\"statusId\":\"" + ItsmIds.STATUS_RESOLVED + "\"}")
                .andExpect(status().isCreated()).andReturn());
        f.send(agent, get("/api/v1/work-packages/" + r.get("number").asLong()), "")
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.status.id").value(ItsmIds.STATUS_RECEIVED.toString()));
    }

    @Test void aRequiredFieldThePortalDoesNotShowNeitherBlocksNorLeaks() throws Exception {
        var name = "내부 분류 " + java.util.UUID.randomUUID().toString().substring(0, 6);
        var field = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + name + "\",\"format\":\"string\",\"isRequired\":true,\"isForAll\":false,"
                + "\"typeIds\":[\"" + ItsmIds.TYPE_INCIDENT + "\"]}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        try {
            f.send(admin, put("/api/v1/projects/" + desk + "/custom-fields"), "{\"customFieldIds\":[\"" + field + "\"]}").andExpect(status().isOk());
            var res = f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"subject\":\"숨은 필드\"}")
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(res).doesNotContain(name);
            f.send(requester, get("/api/v1/portal/desks"), "").andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].fields").value(Matchers.contains(Matchers.empty())));
        } finally {
            f.send(admin, delete("/api/v1/admin/custom-fields/" + field), "");
        }
    }

    @Test void aStatusChangeWithoutACommentIsNewsToo() throws Exception {
        var n = submit();
        f.send(requester, get("/api/v1/portal/tickets/" + n), "").andExpect(status().isOk());
        Thread.sleep(30);
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_HANDLING + "\"}").andExpect(status().isOk());
        f.send(requester, get("/api/v1/portal/tickets"), "").andExpect(jsonPath("$[?(@.number == " + n + ")].hasNews").value(Matchers.contains(true)));
        f.send(requester, get("/api/v1/portal/tickets/" + n), "").andExpect(status().isOk());
        f.send(requester, get("/api/v1/portal/tickets"), "").andExpect(jsonPath("$[?(@.number == " + n + ")].hasNews").value(Matchers.contains(false)));
    }
}

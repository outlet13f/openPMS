package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class PortalSecurityIT {
    @Autowired WebApplicationContext context;
    ItsmFixture f;
    MockHttpSession admin, agent, requester, other;
    String desk;
    long mine;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        other = f.login(ItsmFixture.OTHER_REQUESTER);
        desk = f.newDesk(admin);
        mine = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"급여 명세서 오류\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    @Test void someoneElsesTicketIsNotFoundEverywhere() throws Exception {
        f.send(other, get("/api/v1/portal/tickets/" + mine), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("요청을 찾을 수 없습니다."));
        f.send(other, post("/api/v1/portal/tickets/" + mine + "/comments"), "{\"bodyHtml\":\"<p>x</p>\"}").andExpect(status().isNotFound());
        f.send(other, post("/api/v1/portal/tickets/" + mine + "/status"), "{\"statusId\":\"" + ItsmIds.STATUS_CANCELLED + "\",\"lockVersion\":0}")
                .andExpect(status().isNotFound());
        f.send(other, get("/api/v1/portal/tickets"), "").andExpect(jsonPath("$[*].number", Matchers.not(Matchers.hasItem((int) mine))));
        // a number that does not exist answers the same
        f.send(other, get("/api/v1/portal/tickets/99999999"), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("요청을 찾을 수 없습니다."));
    }

    @Test void requestersCannotUseTheWorkPackageApi() throws Exception {
        f.send(requester, get("/api/v1/work-packages/" + mine), "").andExpect(status().isNotFound());
        f.send(requester, patch("/api/v1/work-packages/" + mine), "{\"lockVersion\":0,\"subject\":\"x\"}").andExpect(status().isNotFound());
        f.send(requester, post("/api/v1/work-packages/query"), "{\"projectId\":\"" + desk + "\",\"spec\":{}}").andExpect(status().isNotFound());
        f.send(requester, post("/api/v1/work-packages/query"), "{\"spec\":{}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.wp.number == " + mine + ")]").isEmpty());
    }

    @Test void internalCommentsNeverReachThePortal() throws Exception {
        f.send(admin, patch("/api/v1/projects/" + desk), "{\"internalComments\":true}").andExpect(status().isOk());
        f.send(admin, post("/api/v1/work-packages/" + mine + "/comments"), "{\"bodyHtml\":\"<p>급여팀 김과장 확인 필요</p>\",\"internal\":true}")
                .andExpect(status().is2xxSuccessful());
        var body = f.send(requester, get("/api/v1/portal/tickets/" + mine), "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("김과장");
        f.send(requester, get("/api/v1/portal/tickets"), "").andExpect(jsonPath("$[?(@.number == " + mine + ")].hasNews").value(Matchers.contains(false)));
    }

    @Test void aStaleStatusChangeDoesNotLeakTheWorkPackage() throws Exception {
        var body = f.send(requester, post("/api/v1/portal/tickets/" + mine + "/status"), "{\"statusId\":\"" + ItsmIds.STATUS_CANCELLED + "\",\"lockVersion\":999}")
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("급여 명세서 오류").doesNotContain("lockVersion");
        f.send(requester, post("/api/v1/portal/tickets/" + mine + "/status"), "{\"statusId\":\"" + ItsmIds.STATUS_CANCELLED + "\",\"lockVersion\":999}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.current").doesNotExist());
    }

    @Test void requestersOnlyMakeTheirWorkflowMoves() throws Exception {
        // 접수 → 해결 is not a 요청자 transition
        var lock = f.body(f.send(requester, get("/api/v1/portal/tickets/" + mine), "").andReturn()).get("lockVersion").asInt();
        f.send(requester, post("/api/v1/portal/tickets/" + mine + "/status"), "{\"statusId\":\"" + ItsmIds.STATUS_RESOLVED + "\",\"lockVersion\":" + lock + "}")
                .andExpect(status().isUnprocessableContent());
        f.send(requester, get("/api/v1/portal/tickets/" + mine), "")
                .andExpect(jsonPath("$.allowedStatuses[*].id", Matchers.contains(ItsmIds.STATUS_CANCELLED.toString())));
    }

    @Test void anAdminRequesterAlsoFollowsTheRequesterWorkflow() throws Exception {
        var n = f.body(f.send(admin, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"관리자의 요청\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        f.send(admin, get("/api/v1/portal/tickets/" + n), "")
                .andExpect(jsonPath("$.allowedStatuses[*].id", Matchers.contains(ItsmIds.STATUS_CANCELLED.toString())));
    }

    @Test void requestersAttachAndDownloadOnlyOnTheirOwnTickets() throws Exception {
        var file = new MockMultipartFile("file", "screen.txt", "text/plain", "로그".getBytes(StandardCharsets.UTF_8));
        var made = f.body(f.mvc.perform(multipart("/api/v1/portal/tickets/" + mine + "/attachments")
                .file(file).session(requester).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        var id = made.get("id").asString();
        f.send(requester, get("/api/v1/portal/tickets/" + mine + "/attachments/" + id + "/content"), "").andExpect(status().isOk());
        f.send(other, get("/api/v1/portal/tickets/" + mine + "/attachments/" + id + "/content"), "").andExpect(status().isNotFound());
        f.mvc.perform(multipart("/api/v1/portal/tickets/" + mine + "/attachments")
                .file(file).session(other).with(csrf()))
                .andExpect(status().isNotFound());
        // an attachment of another work package, asked for through my ticket
        var foreign = f.body(f.send(agent, post("/api/v1/projects/" + desk + "/work-packages"), "{\"subject\":\"내부 작업\",\"typeId\":\""
                + ItsmIds.TYPE_INCIDENT + "\",\"requesterId\":\"" + ItsmFixture.DOYOON + "\"}").andReturn()).get("number").asLong();
        var foreignFile = f.body(f.mvc.perform(multipart("/api/v1/work-packages/" + foreign + "/attachments")
                .file(file).session(agent).with(csrf()))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        f.send(requester, get("/api/v1/portal/tickets/" + mine + "/attachments/" + foreignFile + "/content"), "").andExpect(status().isNotFound());
    }
}

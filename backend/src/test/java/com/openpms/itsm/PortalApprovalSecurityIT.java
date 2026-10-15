package com.openpms.itsm;

import static com.openpms.db.Tables.CUSTOM_FIELDS;
import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The approval inbox is a second way in for non-members: it shows an approver their own pending rows and nothing more. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class PortalApprovalSecurityIT {
    static final String ALEX = "alex.kim@openpms.local";
    static final String HANEUL = "haneul.lee@openpms.local";
    static final String STEPS = "[{\"stepNo\":1,\"kind\":\"requester_manager\"},{\"stepNo\":2,\"kind\":\"group_lead\"}]";
    static final String NOT_FOUND = "승인 요청을 찾을 수 없습니다.";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    ItsmFixture f;
    MockHttpSession admin, agent, requester, other, alex;
    String desk, group, field, option, fieldName, outside, outsideName;
    UUID alexId, managerBefore;
    long n;
    String step1, step2, file;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        other = f.login(ItsmFixture.OTHER_REQUESTER);
        alex = f.login(ALEX);
        desk = f.newDesk(admin);
        alexId = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(ALEX)).fetchOne(USERS.ID);
        managerBefore = db.select(USERS.MANAGER_ID).from(USERS).where(USERS.ID.eq(ItsmFixture.MINJI)).fetchOne(USERS.MANAGER_ID);
        f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + alexId + "\"}").andExpect(status().isOk());
        group = f.body(f.send(admin, post("/api/v1/admin/groups"), "{\"name\":\"승인 보안 " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"leadUserId\":\"" + ItsmFixture.JUNHO + "\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        // the item's form field, and a portal field outside the item that an agent fills in
        fieldName = "장비 종류 " + UUID.randomUUID().toString().substring(0, 6);
        var cf = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + fieldName + "\",\"format\":\"list\",\"isForAll\":false,"
                + "\"options\":[{\"value\":\"노트북\"},{\"value\":\"데스크톱\"}],\"typeIds\":[\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\"]}")
                .andExpect(status().isCreated()).andReturn());
        field = cf.get("id").asString();
        option = cf.get("options").get(0).get("id").asString();
        outsideName = "자산 번호 " + UUID.randomUUID().toString().substring(0, 6);
        outside = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + outsideName + "\",\"format\":\"string\",\"isForAll\":false,"
                + "\"typeIds\":[\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\"]}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        db.update(CUSTOM_FIELDS).set(CUSTOM_FIELDS.PORTAL_VISIBLE, true).where(CUSTOM_FIELDS.ID.eq(UUID.fromString(outside))).execute();
        f.send(admin, put("/api/v1/projects/" + desk + "/custom-fields"), "{\"customFieldIds\":[\"" + field + "\",\"" + outside + "\"]}").andExpect(status().isOk());
        var item = f.newItem(admin, desk, "노트북 신청 " + UUID.randomUUID().toString().substring(0, 6), STEPS, UUID.fromString(group));
        f.send(admin, patch("/api/v1/projects/" + desk + "/service-desk/catalog/items/" + item),
                "{\"fields\":[{\"customFieldId\":\"" + field + "\",\"isRequired\":true}]}").andExpect(status().isOk());
        n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"catalogItemId\":\"" + item + "\",\"subject\":\"노트북 교체\","
                + "\"customValues\":{\"" + field + "\":\"" + option + "\"}}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        var wp = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne(WORK_PACKAGES.ID);
        var rows = db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wp)).orderBy(TICKET_APPROVALS.STEP_NO).fetch();
        step1 = rows.get(0).getId().toString();
        step2 = rows.get(1).getId().toString();
        var upload = new MockMultipartFile("file", "견적서.txt", "text/plain", "견적 120만원".getBytes(StandardCharsets.UTF_8));
        file = f.body(f.mvc.perform(multipart("/api/v1/portal/tickets/" + n + "/attachments").file(upload).session(requester).with(csrf()))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    @AfterEach void tearDown() throws Exception {
        f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), managerBefore == null ? "{\"managerId\":null}" : "{\"managerId\":\"" + managerBefore + "\"}")
                .andExpect(status().isOk());
        f.send(admin, delete("/api/v1/admin/custom-fields/" + field), "");
        f.send(admin, delete("/api/v1/admin/custom-fields/" + outside), "");
        f.send(admin, delete("/api/v1/admin/groups/" + group), "");
    }

    @Test void theApproverSeesTheirRowAndTheFile() throws Exception {
        f.send(alex, get("/api/v1/portal/approvals/" + step1), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value((int) n))
                .andExpect(jsonPath("$.files[0].fileName").value("견적서.txt"));
        var body = f.mvc.perform(get("/api/v1/portal/approvals/" + step1 + "/attachments/" + file + "/content").session(alex))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("견적 120만원");
    }

    @Test void someoneElseGetsTheSame404Everywhere() throws Exception {
        f.send(other, get("/api/v1/portal/approvals/" + step1), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(other, post("/api/v1/portal/approvals/" + step1 + "/decision"), "{\"decision\":\"approve\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(other, post("/api/v1/portal/approvals/" + step1 + "/decision"), "{\"decision\":\"nonsense\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(other, get("/api/v1/portal/approvals/" + step1 + "/attachments/" + file + "/content"), "")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(other, get("/api/v1/portal/approvals"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].number", Matchers.not(Matchers.hasItem((int) n))));
        // the requester is not an approver of their own request
        f.send(requester, get("/api/v1/portal/approvals/" + step1), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        // a row that does not exist answers the same
        var none = UUID.randomUUID();
        f.send(alex, get("/api/v1/portal/approvals/" + none), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(alex, post("/api/v1/portal/approvals/" + none + "/decision"), "{\"decision\":\"approve\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        // nothing was decided by any of it
        f.send(alex, get("/api/v1/portal/approvals/" + step1), "").andExpect(status().isOk());
    }

    @Test void aWaitingApproverSeesNothingUntilTheirTurn() throws Exception {
        f.send(agent, get("/api/v1/portal/approvals/" + step2), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(agent, post("/api/v1/portal/approvals/" + step2 + "/decision"), "{\"decision\":\"approve\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(agent, get("/api/v1/portal/approvals/" + step2 + "/attachments/" + file + "/content"), "").andExpect(status().isNotFound());
        f.send(agent, get("/api/v1/portal/approvals"), "").andExpect(jsonPath("$[*].number", Matchers.not(Matchers.hasItem((int) n))));
        f.send(alex, post("/api/v1/portal/approvals/" + step1 + "/decision"), "{\"decision\":\"approve\"}").andExpect(status().isOk());
        f.send(agent, get("/api/v1/portal/approvals"), "").andExpect(jsonPath("$[*].number", Matchers.hasItem((int) n)));
        // and alex's row, decided, is gone from alex's side
        f.send(alex, get("/api/v1/portal/approvals/" + step1), "").andExpect(status().isNotFound());
    }

    @Test void theDetailCarriesNoInternalsEmailsOrOtherFields() throws Exception {
        f.send(admin, patch("/api/v1/projects/" + desk), "{\"internalComments\":true}").andExpect(status().isOk());
        f.send(admin, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>예산 담당 박과장 메모</p>\",\"internal\":true}")
                .andExpect(status().is2xxSuccessful());
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andExpect(status().isOk()).andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"customValues\":{\"" + outside + "\":\"A-7781\"}}")
                .andExpect(status().isOk());
        // alex approves with a note; the next approver does not read it
        f.send(alex, post("/api/v1/portal/approvals/" + step1 + "/decision"), "{\"decision\":\"approve\",\"comment\":\"알렉스 의견 비공개\"}")
                .andExpect(status().isOk());
        var body = f.send(agent, get("/api/v1/portal/approvals/" + step2), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name").value(Matchers.contains(fieldName)))
                .andExpect(jsonPath("$.fields[0].value").value("노트북"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("박과장").doesNotContain("@openpms.local").doesNotContain(outsideName).doesNotContain("A-7781")
                .doesNotContain("알렉스 의견").doesNotContain("lockVersion");
        var inbox = f.send(agent, get("/api/v1/portal/approvals"), "").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(inbox).doesNotContain("@openpms.local").doesNotContain("박과장");
    }

    @Test void theAgentPanelNeedsMembershipAndHandling() throws Exception {
        f.send(other, get("/api/v1/work-packages/" + n + "/approvals"), "").andExpect(status().isNotFound());
        f.send(alex, get("/api/v1/work-packages/" + n + "/approvals"), "").andExpect(status().isNotFound());
        f.send(alex, patch("/api/v1/work-packages/" + n + "/approvals/" + step1), "{\"approverId\":\"" + ItsmFixture.DOYOON + "\"}")
                .andExpect(status().isNotFound());
        // a member who may look at tickets but not handle them
        var role = f.body(f.send(admin, post("/api/v1/admin/roles"), "{\"name\":\"티켓 열람 " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"scope\":\"project\",\"permissions\":[\"wp.view\",\"service.view\"]}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        var haneulId = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(HANEUL)).fetchOne(USERS.ID);
        var projectId = db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(desk)).fetchOne(PROJECTS.ID);
        try {
            f.send(admin, post("/api/v1/projects/" + projectId + "/members"), "{\"principalIds\":[\"" + haneulId + "\"],\"roleIds\":[\"" + role + "\"]}")
                    .andExpect(status().is2xxSuccessful());
            var haneul = f.login(HANEUL);
            f.send(haneul, get("/api/v1/work-packages/" + n + "/approvals"), "").andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].decision").value("pending"));
            f.send(haneul, patch("/api/v1/work-packages/" + n + "/approvals/" + step1), "{\"approverId\":\"" + ItsmFixture.DOYOON + "\"}")
                    .andExpect(status().isForbidden());
            assertThat(db.select(TICKET_APPROVALS.APPROVER_ID).from(TICKET_APPROVALS).where(TICKET_APPROVALS.ID.eq(UUID.fromString(step1)))
                    .fetchOne(TICKET_APPROVALS.APPROVER_ID)).isEqualTo(alexId);
        } finally {
            db.deleteFrom(MEMBERS).where(MEMBERS.PROJECT_ID.eq(projectId), MEMBERS.USER_ID.eq(haneulId)).execute();
            f.send(admin, delete("/api/v1/admin/roles/" + role), "");
        }
    }
}

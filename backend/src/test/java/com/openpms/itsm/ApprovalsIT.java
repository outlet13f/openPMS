package com.openpms.itsm;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.common.ApiException;
import com.openpms.common.Mailer;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.db.tables.records.TicketApprovalsRecord;
import com.openpms.wp.WorkPackageService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Deciding catalog requests: the approval inbox, approve and reject, leaving 승인 대기 and the agent's approval panel (ITSM 2단계). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ApprovalsIT {
    static final String ALEX = "alex.kim@openpms.local";
    static final String HANEUL = "haneul.lee@openpms.local";
    static final String TWO_STEPS = "[{\"stepNo\":1,\"kind\":\"requester_manager\"},{\"stepNo\":2,\"kind\":\"group_lead\"}]";
    static final String ONE_STEP = "[{\"stepNo\":1,\"kind\":\"requester_manager\"}]";
    static final String ONE_STEP_TWO_PEOPLE = "[{\"stepNo\":1,\"kind\":\"requester_manager\"},{\"stepNo\":1,\"kind\":\"group_lead\"}]";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired PortalMail mail;
    @Autowired Current current;
    @Autowired SlaJobs jobs;
    @Autowired WorkPackageService workPackages;
    @MockitoBean Mailer mailer;
    ItsmFixture f;
    MockHttpSession admin, agent, requester, alex;
    String desk, group;
    UUID alexId, managerBefore;

    @BeforeEach void setUp() throws Exception {
        Mockito.when(mailer.send(anyString(), anyString(), anyString())).thenReturn(true);
        Mockito.when(mailer.link(anyString())).thenAnswer(i -> "http://app.test" + i.getArgument(0));
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        alex = f.login(ALEX);
        desk = f.newDesk(admin);
        alexId = idOf(ALEX);
        managerBefore = db.select(USERS.MANAGER_ID).from(USERS).where(USERS.ID.eq(ItsmFixture.MINJI)).fetchOne(USERS.MANAGER_ID);
        f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + alexId + "\"}").andExpect(status().isOk());
        group = f.body(f.send(admin, post("/api/v1/admin/groups"), "{\"name\":\"승인 시험 " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"leadUserId\":\"" + ItsmFixture.JUNHO + "\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        // service requests at 보통 run on the 24x7 calendar: 240 minutes to resolve
        var matrix = new StringBuilder();
        for (int i = 1; i <= 3; i++)
            for (int u = 1; u <= 3; u++) {
                if (matrix.length() > 0) matrix.append(',');
                matrix.append("{\"impact\":").append(i).append(",\"urgency\":").append(u).append(",\"priorityId\":\"").append(ItsmFixture.NORMAL).append("\"}");
            }
        f.send(admin, put("/api/v1/projects/" + desk + "/service-desk/settings"), "{\"policies\":["
                + "{\"typeId\":\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\",\"priorityId\":\"" + ItsmFixture.NORMAL + "\",\"calendarId\":\"" + ItsmFixture.CAL_24X7
                + "\",\"firstResponseMinutes\":60,\"resolveMinutes\":240}],\"matrix\":[" + matrix + "]}").andExpect(status().isOk());
    }

    @AfterEach void tearDown() throws Exception {
        f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), managerBefore == null ? "{\"managerId\":null}" : "{\"managerId\":\"" + managerBefore + "\"}")
                .andExpect(status().isOk());
        f.send(admin, delete("/api/v1/admin/groups/" + group), "");
    }

    private UUID idOf(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
    }

    private String name(UUID id) {
        return db.select(USERS.NAME).from(USERS).where(USERS.ID.eq(id)).fetchOne(USERS.NAME);
    }

    /** 최민지 asks for an item with these steps; returns the request number. */
    private long request(String steps) throws Exception {
        var item = f.newItem(admin, desk, "승인 항목 " + UUID.randomUUID().toString().substring(0, 6), steps, UUID.fromString(group));
        return f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"),
                        "{\"catalogItemId\":\"" + item + "\",\"subject\":\"모니터 추가 신청\",\"descriptionHtml\":\"<p>듀얼 모니터가 필요합니다</p>\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status.name").value("승인 대기")).andReturn()).get("number").asLong();
    }

    private UUID wpId(long n) {
        return db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne(WORK_PACKAGES.ID);
    }

    private List<TicketApprovalsRecord> rows(long n) {
        return db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId(n)))
                .orderBy(TICKET_APPROVALS.STEP_NO, TICKET_APPROVALS.APPROVER_KIND.desc()).fetch();
    }

    private UUID statusOf(long n) {
        return db.select(WORK_PACKAGES.STATUS_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne(WORK_PACKAGES.STATUS_ID);
    }

    /** The id of my inbox row for request n (fails when it is not there). */
    private String inboxId(MockHttpSession who, long n) throws Exception {
        var list = f.body(f.send(who, get("/api/v1/portal/approvals"), "").andExpect(status().isOk()).andReturn());
        for (var x : list) if (x.get("number").asLong() == n) return x.get("id").asString();
        throw new AssertionError("request " + n + " is not in the inbox");
    }

    private String decision(String d, String comment) {
        return "{\"decision\":\"" + d + "\"" + (comment == null ? "" : ",\"comment\":\"" + comment + "\"") + "}";
    }

    @Test void twoStepsThenReceivedAndTheClockMoves() throws Exception {
        var n = request(TWO_STEPS);
        var wp = wpId(n);
        var due = db.select(SERVICE_TICKETS.RESOLVE_DUE_AT).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.RESOLVE_DUE_AT);
        assertThat(due).isNotNull();
        f.send(alex, get("/api/v1/portal/approvals"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.number == " + n + ")].requester").value(Matchers.contains(name(ItsmFixture.MINJI))))
                .andExpect(jsonPath("$[?(@.number == " + n + ")].stepNo").value(Matchers.contains(1)));
        f.send(alex, post("/api/v1/portal/approvals/" + inboxId(alex, n) + "/decision"), decision("approve", "좋습니다"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("approved"));
        var r = rows(n);
        assertThat(r.get(0).getDecision()).isEqualTo("approved");
        assertThat(r.get(0).getComment()).isEqualTo("좋습니다");
        assertThat(r.get(1).getDecision()).isEqualTo("pending");
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_AWAITING_APPROVAL);
        // pretend the request has waited for approval for 30 minutes
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.PAUSED_SINCE, OffsetDateTime.now().minusMinutes(30)).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).execute();
        f.send(agent, post("/api/v1/portal/approvals/" + inboxId(agent, n) + "/decision"), decision("approve", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("approved"));
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_RECEIVED);
        var t = db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne();
        assertThat(t.getPausedSince()).isNull();
        assertThat(t.getPausedTotalSeconds()).isBetween(1795L, 1810L);
        assertThat(Duration.between(due, t.getResolveDueAt()).toSeconds()).isBetween(1795L, 1810L);
        assertThat(t.getFirstRespondedAt()).as("an approval is not an answer to the requester").isNull();
        f.send(agent, get("/api/v1/work-packages/" + n + "/activities"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].details[*].to", Matchers.hasItems("1단계 " + name(alexId) + " 승인", "2단계 " + name(ItsmFixture.JUNHO) + " 승인")));
        f.send(agent, get("/api/v1/work-packages/" + n + "/approvals"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approver.name").value(name(alexId)))
                .andExpect(jsonPath("$[0].comment").value("좋습니다"))
                .andExpect(jsonPath("$[1].decision").value("approved"));
    }

    private List<String[]> mails(long n) {
        var subject = ArgumentCaptor.forClass(String.class);
        var body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailer, Mockito.atLeast(0)).send(eq(ItsmFixture.REQUESTER), subject.capture(), body.capture());
        var out = new ArrayList<String[]>();
        for (int i = 0; i < subject.getAllValues().size(); i++) {
            if (subject.getAllValues().get(i).contains("#" + n + " ")) out.add(new String[] {subject.getAllValues().get(i), body.getAllValues().get(i)});
        }
        return out;
    }

    @Test void rejectionEndsItWithAReason() throws Exception {
        var n = request(TWO_STEPS);
        mail.run(Instant.now()); // the receipt
        Mockito.clearInvocations(mailer);
        f.send(alex, post("/api/v1/portal/approvals/" + inboxId(alex, n) + "/decision"), decision("reject", "예산 초과"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("rejected"));
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_REJECTED);
        var r = rows(n);
        assertThat(r.get(0).getDecision()).isEqualTo("rejected");
        assertThat(r.get(0).getComment()).as("the agent panel shows the reason too").isEqualTo("예산 초과");
        assertThat(r.get(1).getDecision()).isEqualTo("skipped");
        assertThat(r.get(1).getSkipReason()).isEqualTo("left_approval");
        f.send(requester, get("/api/v1/portal/tickets/" + n), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.status.name").value("반려"))
                .andExpect(jsonPath("$.activity[?(@.notesHtml =~ /.*예산 초과.*/)].author").value(Matchers.contains(name(alexId))));
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[1]).contains("반려").contains("예산 초과");
        f.send(agent, get("/api/v1/portal/approvals"), "").andExpect(jsonPath("$[*].number", Matchers.not(Matchers.hasItem((int) n))));
    }

    @Test void aRejectionNeedsAReason() throws Exception {
        var n = request(TWO_STEPS);
        var id = inboxId(alex, n);
        f.send(alex, post("/api/v1/portal/approvals/" + id + "/decision"), decision("reject", null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("comment"))
                .andExpect(jsonPath("$.errors[0].message").value("반려 사유를 입력하세요."));
        f.send(alex, post("/api/v1/portal/approvals/" + id + "/decision"), decision("reject", "  \\n ")).andExpect(status().isUnprocessableContent());
        f.send(alex, post("/api/v1/portal/approvals/" + id + "/decision"), decision("maybe", null)).andExpect(status().isUnprocessableContent());
        assertThat(rows(n).get(0).getDecision()).isEqualTo("pending");
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_AWAITING_APPROVAL);
    }

    @Test void concurrentDecisionsCountOnce() throws Exception {
        var n = request(ONE_STEP_TWO_PEOPLE);
        var r = rows(n);
        assertThat(r).extracting(TicketApprovalsRecord::getDecision).containsExactly("pending", "pending");
        var sessions = List.of(alex, agent);
        var ids = List.of(inboxId(alex, n), inboxId(agent, n));
        var start = new CountDownLatch(1);
        var codes = new int[2];
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                final int k = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    codes[k] = f.send(sessions.get(k), post("/api/v1/portal/approvals/" + ids.get(k) + "/decision"), decision("approve", null))
                            .andReturn().getResponse().getStatus();
                    return null;
                }));
            }
            start.countDown();
            for (var x : futures) x.get();
        }
        assertThat(codes).as("one decision wins").containsOnlyOnce(200);
        var loser = codes[0] == 200 ? codes[1] : codes[0];
        assertThat(loser).isIn(404, 409);
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_RECEIVED);
        assertThat(db.fetchCount(JOURNALS, JOURNALS.WORK_PACKAGE_ID.eq(wpId(n)),
                DSL.condition("{0} -> 'statusId' ->> 1 = {1}", JOURNALS.CHANGES, ItsmIds.STATUS_RECEIVED.toString()))).isEqualTo(1);
        var after = rows(n);
        assertThat(after).extracting(TicketApprovalsRecord::getDecision).containsExactlyInAnyOrder("approved", "skipped");
        // the winner sends it again: already decided
        var winner = codes[0] == 200 ? 0 : 1;
        f.send(sessions.get(winner), post("/api/v1/portal/approvals/" + ids.get(winner) + "/decision"), decision("approve", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("이미 처리된 승인입니다."));
    }

    @Test void rejectReasonIsPlainText() throws Exception {
        var n = request(TWO_STEPS);
        f.send(alex, post("/api/v1/portal/approvals/" + inboxId(alex, n) + "/decision"), decision("reject", "금액 < 한도 & 기타\\n둘째 줄"))
                .andExpect(status().isOk());
        assertThat(rows(n).get(0).getComment()).isEqualTo("금액 < 한도 & 기타\n둘째 줄");
        var notes = f.body(f.send(requester, get("/api/v1/portal/tickets/" + n), "").andExpect(status().isOk()).andReturn()).get("activity").toString();
        assertThat(notes).contains("금액 &lt; 한도 &amp; 기타").contains("<p>둘째 줄</p>");
    }

    /** An approver's decision and an agent's status change of the same request take the same locks in the same order. */
    @Test void decisionAndCancelDoNotDeadlock() throws Exception {
        for (int round = 0; round < 20; round++) {
            var n = request(ONE_STEP);
            var id = inboxId(alex, n);
            var lock = f.body(f.send(agent, get("/api/v1/work-packages/" + n), "").andExpect(status().isOk()).andReturn()).get("lockVersion").asInt();
            var start = new CountDownLatch(1);
            var codes = new int[2];
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> {
                    start.await();
                    codes[0] = f.send(alex, post("/api/v1/portal/approvals/" + id + "/decision"), decision("approve", null)).andReturn().getResponse().getStatus();
                    return null;
                });
                var b = pool.submit(() -> {
                    start.await();
                    codes[1] = f.send(agent, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_CANCELLED + "\"}")
                            .andReturn().getResponse().getStatus();
                    return null;
                });
                start.countDown();
                a.get();
                b.get();
            }
            assertThat(codes[0]).as("round %d decision", round).isIn(200, 404, 409, 422);
            assertThat(codes[1]).as("round %d cancel", round).isIn(200, 409, 422);
            var row = rows(n).get(0).getDecision();
            if (statusOf(n).equals(ItsmIds.STATUS_RECEIVED)) assertThat(row).isEqualTo("approved");
            else {
                assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_CANCELLED);
                assertThat(row).isEqualTo("skipped");
            }
        }
    }

    @Test void waitingForApprovalDoesNotUseUpTheResponseTarget() throws Exception {
        var n = request(ONE_STEP);
        var wp = wpId(n);
        // the request has waited 90 minutes for approval (the first-response target is 60)
        var start = OffsetDateTime.now().minusMinutes(90);
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.CREATED_AT, start).set(SERVICE_TICKETS.PAUSED_SINCE, start)
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).execute();
        f.send(alex, post("/api/v1/portal/approvals/" + inboxId(alex, n) + "/decision"), decision("approve", null)).andExpect(status().isOk());
        var t = db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne();
        assertThat(Duration.between(OffsetDateTime.now(), t.getFirstResponseDueAt()).toMinutes()).as("about the target after the approval").isBetween(58L, 61L);
        jobs.run(Instant.now());
        var after = db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne();
        assertThat(after.getResponseBreached()).isFalse();
    }

    @Test void copyingATicketInApprovalStartsItReceived() throws Exception {
        var n = request(TWO_STEPS);
        var copy = f.body(f.send(agent, post("/api/v1/work-packages/" + n + "/copy"), "{}").andExpect(status().is2xxSuccessful()).andReturn()).get("number").asLong();
        assertThat(statusOf(copy)).isEqualTo(ItsmIds.STATUS_RECEIVED);
        assertThat(rows(copy)).isEmpty();
        var t = db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId(copy))).fetchOne();
        assertThat(t.getPausedSince()).isNull();
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_AWAITING_APPROVAL);
    }

    @Test void cancellingWhileWaitingSkipsTheRest() throws Exception {
        var n = request(TWO_STEPS);
        var id = inboxId(alex, n);
        var lock = f.body(f.send(requester, get("/api/v1/portal/tickets/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(requester, post("/api/v1/portal/tickets/" + n + "/status"), "{\"statusId\":\"" + ItsmIds.STATUS_CANCELLED + "\",\"lockVersion\":" + lock + "}")
                .andExpect(status().isOk());
        assertThat(rows(n)).allSatisfy(x -> {
            assertThat(x.getDecision()).isEqualTo("skipped");
            assertThat(x.getSkipReason()).isEqualTo("left_approval");
        });
        f.send(alex, get("/api/v1/portal/approvals"), "").andExpect(jsonPath("$[*].number", Matchers.not(Matchers.hasItem((int) n))));
        f.send(alex, get("/api/v1/portal/approvals/" + id), "").andExpect(status().isNotFound());
        f.send(alex, post("/api/v1/portal/approvals/" + id + "/decision"), decision("approve", null)).andExpect(status().isNotFound());
    }

    @Test void adminForcingReceivedSkipsTheRest() throws Exception {
        var n = request(TWO_STEPS);
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andExpect(status().isOk()).andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_RECEIVED + "\"}")
                .andExpect(status().isOk());
        assertThat(rows(n)).allSatisfy(x -> assertThat(x.getDecision()).isEqualTo("skipped"));
        f.send(agent, get("/api/v1/work-packages/" + n + "/activities"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].details[*].to", Matchers.hasItem("남은 승인 건너뜀")));
    }

    @Test void agentsCannotSkipTheApprovals() throws Exception {
        var n = request(TWO_STEPS);
        var lock = f.body(f.send(agent, get("/api/v1/work-packages/" + n), "").andExpect(status().isOk()).andReturn()).get("lockVersion").asInt();
        f.send(agent, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_RECEIVED + "\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(rows(n).get(0).getDecision()).isEqualTo("pending");
    }

    @Test void agentReplacesAnApprover() throws Exception {
        var n = request(TWO_STEPS);
        var r = rows(n);
        var haneulId = idOf(HANEUL);
        var alexRow = inboxId(alex, n);
        f.send(agent, patch("/api/v1/work-packages/" + n + "/approvals/" + r.get(0).getId()), "{\"approverId\":\"" + haneulId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approver.id").value(haneulId.toString()));
        var haneul = f.login(HANEUL);
        f.send(haneul, get("/api/v1/portal/approvals/" + inboxId(haneul, n)), "").andExpect(status().isOk());
        f.send(alex, get("/api/v1/portal/approvals/" + alexRow), "").andExpect(status().isNotFound());
        f.send(agent, get("/api/v1/work-packages/" + n + "/activities"), "").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].details[*].to", Matchers.hasItem("1단계 승인자 변경: " + name(alexId) + " → " + name(haneulId))));
        // the requester cannot approve their own request; nor can a decided row change hands
        f.send(agent, patch("/api/v1/work-packages/" + n + "/approvals/" + r.get(1).getId()), "{\"approverId\":\"" + ItsmFixture.MINJI + "\"}")
                .andExpect(status().isUnprocessableContent());
        f.send(haneul, post("/api/v1/portal/approvals/" + inboxId(haneul, n) + "/decision"), decision("approve", null)).andExpect(status().isOk());
        f.send(agent, patch("/api/v1/work-packages/" + n + "/approvals/" + r.get(0).getId()), "{\"approverId\":\"" + alexId + "\"}")
                .andExpect(status().isUnprocessableContent());
        // a row of no request here
        f.send(agent, patch("/api/v1/work-packages/" + n + "/approvals/" + UUID.randomUUID()), "{\"approverId\":\"" + alexId + "\"}")
                .andExpect(status().isNotFound());
    }

    /** Acting.APPROVAL only moves 승인 대기 to 접수 or 반려 (Task 5 review). */
    @Test void approvalActingRefusesOtherMoves() throws Exception {
        var ctx = new Ctx(alexId, current::snapshot);
        var plain = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"프린터 고장\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        assertThatThrownBy(() -> workPackages.statusForApproval(ctx, found(ctx, plain), ItsmIds.STATUS_REJECTED))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(422));
        assertThat(statusOf(plain)).isEqualTo(ItsmIds.STATUS_RECEIVED);
        var n = request(TWO_STEPS);
        assertThatThrownBy(() -> workPackages.statusForApproval(ctx, found(ctx, n), ItsmIds.STATUS_RESOLVED))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(422));
        assertThat(statusOf(n)).isEqualTo(ItsmIds.STATUS_AWAITING_APPROVAL);
    }

    private WorkPackageService.Found found(Ctx ctx, long n) {
        var w = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne();
        return new WorkPackageService.Found(w, ctx.s().project(w.getProjectId()).orElseThrow());
    }
}

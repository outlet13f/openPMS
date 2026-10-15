package com.openpms.itsm;

import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.common.Mailer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
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

/** Mail to approvers when it is their turn and once more after three working days (ITSM 2단계). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ApprovalMailIT {
    static final String ALEX = "alex.kim@openpms.local";
    static final String ONE_STEP = "[{\"stepNo\":1,\"kind\":\"requester_manager\"}]";

    @Autowired WebApplicationContext context;
    @Autowired ApprovalMail mail;
    @Autowired DSLContext db;
    @MockitoBean Mailer mailer;
    ItsmFixture f;
    MockHttpSession admin, requester, alex;
    String desk;
    UUID alexId, managerBefore;

    @BeforeEach void setUp() throws Exception {
        Mockito.when(mailer.send(anyString(), anyString(), anyString())).thenReturn(true);
        Mockito.when(mailer.link(anyString())).thenAnswer(i -> "http://app.test" + i.getArgument(0));
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        requester = f.login(ItsmFixture.REQUESTER);
        alex = f.login(ALEX);
        desk = f.newDesk(admin);
        alexId = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(ALEX)).fetchOne(USERS.ID);
        managerBefore = db.select(USERS.MANAGER_ID).from(USERS).where(USERS.ID.eq(ItsmFixture.MINJI)).fetchOne(USERS.MANAGER_ID);
        f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + alexId + "\"}").andExpect(status().isOk());
        mail.run(Instant.now()); // clear what is due for other requests on the shared database
        Mockito.clearInvocations(mailer);
    }

    @AfterEach void tearDown() throws Exception {
        db.update(USERS).set(USERS.STATUS, "active").where(USERS.ID.eq(alexId)).execute();
        f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), managerBefore == null ? "{\"managerId\":null}" : "{\"managerId\":\"" + managerBefore + "\"}")
                .andExpect(status().isOk());
    }

    private long request() throws Exception {
        var item = f.newItem(admin, desk, "메일 항목 " + UUID.randomUUID().toString().substring(0, 6), ONE_STEP, null);
        return f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"),
                        "{\"catalogItemId\":\"" + item + "\",\"subject\":\"모니터 추가 신청\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    private UUID wpId(long n) {
        return db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne(WORK_PACKAGES.ID);
    }

    private List<String[]> mails(long n) {
        var subject = ArgumentCaptor.forClass(String.class);
        var body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailer, Mockito.atLeast(0)).send(eq(ALEX), subject.capture(), body.capture());
        var out = new ArrayList<String[]>();
        for (int i = 0; i < subject.getAllValues().size(); i++) {
            if (subject.getAllValues().get(i).contains("#" + n + " ")) out.add(new String[] {subject.getAllValues().get(i), body.getAllValues().get(i)});
        }
        return out;
    }

    private String approvalId(long n) {
        return db.select(TICKET_APPROVALS.ID).from(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId(n))).fetchOne(TICKET_APPROVALS.ID).toString();
    }

    private void notifiedDaysAgo(long n, int days) {
        db.update(TICKET_APPROVALS).set(TICKET_APPROVALS.NOTIFIED_AT, OffsetDateTime.now().minusDays(days)).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId(n))).execute();
    }

    @Test void requestThenReminderEachOnce() throws Exception {
        var n = request();
        mail.run(Instant.now());
        var first = mails(n);
        assertThat(first).hasSize(1);
        assertThat(first.get(0)[0]).isEqualTo("[openPMS] 승인 요청: #" + n + " 모니터 추가 신청");
        assertThat(first.get(0)[1]).contains(db.select(USERS.NAME).from(USERS).where(USERS.ID.eq(ItsmFixture.MINJI)).fetchOne(USERS.NAME))
                .contains("/portal/approvals/" + approvalId(n)).doesNotContain("@");
        Mockito.clearInvocations(mailer);
        mail.run(Instant.now());
        assertThat(mails(n)).isEmpty();
        // too early for a reminder: just notified
        assertThat(db.select(TICKET_APPROVALS.NOTIFIED_AT).from(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId(n))).fetchOne(TICKET_APPROVALS.NOTIFIED_AT)).isNotNull();

        notifiedDaysAgo(n, 10); // at least five working days
        mail.run(Instant.now());
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[0]).isEqualTo("[openPMS] 승인 대기 중: #" + n + " 모니터 추가 신청");
        Mockito.clearInvocations(mailer);
        mail.run(Instant.now());
        assertThat(mails(n)).isEmpty();
    }

    @Test void noReminderOnceDecided() throws Exception {
        var n = request();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        f.send(alex, post("/api/v1/portal/approvals/" + approvalId(n) + "/decision"), "{\"decision\":\"approve\"}").andExpect(status().isOk());
        notifiedDaysAgo(n, 10);
        mail.run(Instant.now());
        assertThat(mails(n)).isEmpty();
    }

    @Test void oneDayIsNeverThreeWorkingDays() throws Exception {
        var n = request();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        notifiedDaysAgo(n, 1);
        mail.run(Instant.now());
        assertThat(mails(n)).as("one day is never three working days").isEmpty();
    }

    @Test void inactiveApproverIsMarkedButNotMailed() throws Exception {
        var n = request();
        db.update(USERS).set(USERS.STATUS, "locked").where(USERS.ID.eq(alexId)).execute();
        mail.run(Instant.now());
        assertThat(mails(n)).isEmpty();
        assertThat(db.select(TICKET_APPROVALS.NOTIFIED_AT).from(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId(n))).fetchOne(TICKET_APPROVALS.NOTIFIED_AT)).isNotNull();
        db.update(USERS).set(USERS.STATUS, "active").where(USERS.ID.eq(alexId)).execute();
        mail.run(Instant.now());
        assertThat(mails(n)).isEmpty();
    }
}

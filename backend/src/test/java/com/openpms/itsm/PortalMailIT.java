package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.common.Mailer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class PortalMailIT {
    @Autowired WebApplicationContext context;
    @Autowired PortalMail mail;
    @Autowired DSLContext db;
    @MockitoBean Mailer mailer;
    ItsmFixture f;
    MockHttpSession admin, agent, requester;
    String desk;

    @BeforeEach void setUp() throws Exception {
        Mockito.when(mailer.send(anyString(), anyString(), anyString())).thenReturn(true);
        Mockito.when(mailer.link(anyString())).thenAnswer(i -> "http://app.test" + i.getArgument(0));
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        desk = f.newDesk(admin);
        mail.run(Instant.now()); // clear what is due for other tickets
        Mockito.clearInvocations(mailer);
    }

    @Test void receiptThenOneMailForTheAnswersButNeverInternalNotes() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"프린터 고장\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        assertThat(mails(n)).hasSize(1).allSatisfy(m -> assertThat(m[0]).contains("접수"));
        Mockito.clearInvocations(mailer);

        f.send(admin, patch("/api/v1/projects/" + desk), "{\"internalComments\":true}").andExpect(status().isOk());
        f.send(agent, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>토너 교체 예정</p>\"}").andExpect(status().is2xxSuccessful());
        f.send(admin, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>총무팀 비용 승인 필요</p>\",\"internal\":true}").andExpect(status().is2xxSuccessful());
        mail.run(Instant.now());                    // younger than a minute: wait
        assertThat(mails(n)).isEmpty();
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[1]).contains("토너 교체 예정").doesNotContain("총무팀").contains("/portal/tickets/" + n);
        Mockito.clearInvocations(mailer);
        mail.run(Instant.now().plusSeconds(180));
        assertThat(mails(n)).isEmpty();
    }

    @Test void theRequestersOwnCommentsAreNotMailedBack() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"모니터 깜빡임\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        f.send(requester, post("/api/v1/portal/tickets/" + n + "/comments"), "{\"bodyHtml\":\"<p>사진 첨부합니다</p>\"}").andExpect(status().isOk());
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).isEmpty();
    }

    @Test void aPlainEditThenAMergedStatusChangeStillMailsTheStatusOnce() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"메일 안 옴\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_HANDLING + "\"}").andExpect(status().isOk());
        // pretend the status journal is two minutes old and that a mail went out a minute ago: the next change merges into it,
        // so only updated_at (not created_at) is after the last mail
        var wpId = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).join(SERVICE_TICKETS).on(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(WORK_PACKAGES.ID))
                .where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne().value1();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var latest = db.select(JOURNALS.ID).from(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wpId)).orderBy(JOURNALS.VERSION.desc()).limit(1).fetchOne().value1();
        backdate(latest, now.minusMinutes(2));
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, now.minusMinutes(1)).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId)).execute();
        lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"subject\":\"메일 안 옴 (재확인)\"}").andExpect(status().isOk());
        assertThat(db.fetchCount(JOURNALS, JOURNALS.ID.eq(latest))).isEqualTo(1); // merged, not a new entry
        mail.run(Instant.now());                    // the merge is younger than a minute: wait
        assertThat(mails(n)).isEmpty();
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[1]).contains("상태").doesNotContain("재확인");
    }

    @Test void aMailedStatusIsNotMailedAgainWhenALaterPlainEditMergesIntoItsJournal() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"로그인 불가\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_RESOLVED + "\"}").andExpect(status().isOk());
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[0]).contains("해결되었습니다");
        assertThat(mails(n).get(0)[1]).contains("상태");
        Mockito.clearInvocations(mailer);

        var wpId = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne().value1();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var latest = db.select(JOURNALS.ID).from(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wpId)).orderBy(JOURNALS.VERSION.desc()).limit(1).fetchOne().value1();
        backdate(latest, now.minusMinutes(2));
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, now.minusMinutes(1)).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId)).execute();
        lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"subject\":\"로그인 불가 (재확인)\"}").andExpect(status().isOk());
        assertThat(db.fetchCount(JOURNALS, JOURNALS.ID.eq(latest))).isEqualTo(1); // merged into the mailed entry
        assertThat(db.select(JOURNALS.UPDATED_AT).from(JOURNALS).where(JOURNALS.ID.eq(latest)).fetchOne().value1()).isAfter(now.minusMinutes(1));
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).isEmpty();
    }

    @Test void aStatusChangeStillSettlingWaitsForTheNextMailInsteadOfBeingSwallowed() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"와이파이 느림\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        f.send(agent, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>공유기 확인 중</p>\"}").andExpect(status().is2xxSuccessful());
        var lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + ItsmIds.STATUS_RESOLVED + "\"}").andExpect(status().isOk());
        // the comment is three minutes old (after a mail five minutes ago); the status change is only seconds old
        var wpId = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne().value1();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var comment = db.select(JOURNALS.ID).from(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wpId), JOURNALS.NOTES_TEXT.ne("")).fetchOne().value1();
        backdate(comment, now.minusMinutes(3));
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, now.minusMinutes(5)).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId)).execute();

        mail.run(Instant.now());                    // only the comment has settled
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[1]).contains("공유기 확인 중").doesNotContain("상태");
        assertThat(mails(n).get(0)[0]).doesNotContain("해결되었습니다");
        Mockito.clearInvocations(mailer);

        mail.run(Instant.now().plusSeconds(120));   // now the status change has settled
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[1]).contains("→ 해결").doesNotContain("공유기 확인 중");
        assertThat(mails(n).get(0)[0]).contains("해결되었습니다");
        Mockito.clearInvocations(mailer);

        mail.run(Instant.now().plusSeconds(240));
        assertThat(mails(n)).isEmpty();
    }

    @Test void aStatusTheRequesterChangedThemselvesIsNotToldBackAndTheNextResolveIsAnnounced() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"VPN 접속 불가\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        var wpId = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne().value1();
        setStatus(admin, n, ItsmIds.STATUS_RESOLVED);
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[1]).contains("→ 해결");
        Mockito.clearInvocations(mailer);
        // the clock in this test is the real one: put the marker back where a real run would have left it
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId)).execute();

        var lock = f.body(f.send(requester, get("/api/v1/portal/tickets/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(requester, post("/api/v1/portal/tickets/" + n + "/status"), "{\"statusId\":\"" + ItsmIds.STATUS_HANDLING + "\",\"lockVersion\":" + lock + "}")
                .andExpect(status().isOk());
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).isEmpty();
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId)).execute();

        setStatus(admin, n, ItsmIds.STATUS_RESOLVED);
        mail.run(Instant.now().plusSeconds(120));
        assertThat(mails(n)).hasSize(1);
        assertThat(mails(n).get(0)[0]).contains("해결되었습니다");
        assertThat(mails(n).get(0)[1]).contains("처리 중 → 해결");
    }

    private void setStatus(MockHttpSession as, long n, UUID statusId) throws Exception {
        var lock = f.body(f.send(as, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(as, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"statusId\":\"" + statusId + "\"}").andExpect(status().isOk());
    }

    /** The mails sent to the requester about this ticket, as {subject, body}; the shared DB may hold other tickets that are due. */
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

    /** The journals trigger sets updated_at to now(); this keeps it so a test can place an entry in the past. */
    private void backdate(UUID journalId, OffsetDateTime at) {
        db.transaction(c -> {
            var tx = DSL.using(c);
            tx.execute("set local openpms.keep_updated_at = 'on'");
            tx.update(JOURNALS).set(JOURNALS.CREATED_AT, at).set(JOURNALS.UPDATED_AT, at).where(JOURNALS.ID.eq(journalId)).execute();
        });
    }
}

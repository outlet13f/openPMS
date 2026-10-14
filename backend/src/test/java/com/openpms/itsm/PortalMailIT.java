package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import org.jooq.DSLContext;
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
        mail.run(Instant.now().plusSeconds(120)); // clear what other tests left
        Mockito.clearInvocations(mailer);
    }

    @Test void receiptThenOneMailForTheAnswersButNeverInternalNotes() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"프린터 고장\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.verify(mailer).send(eq(ItsmFixture.REQUESTER), contains("접수"), anyString());
        Mockito.clearInvocations(mailer);

        f.send(admin, patch("/api/v1/projects/" + desk), "{\"internalComments\":true}").andExpect(status().isOk());
        f.send(agent, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>토너 교체 예정</p>\"}").andExpect(status().is2xxSuccessful());
        f.send(admin, post("/api/v1/work-packages/" + n + "/comments"), "{\"bodyHtml\":\"<p>총무팀 비용 승인 필요</p>\",\"internal\":true}").andExpect(status().is2xxSuccessful());
        mail.run(Instant.now());                    // younger than a minute: wait
        Mockito.verifyNoInteractions(mailer);
        mail.run(Instant.now().plusSeconds(120));
        var body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailer).send(eq(ItsmFixture.REQUESTER), anyString(), body.capture());
        assertThat(body.getValue()).contains("토너 교체 예정").doesNotContain("총무팀").contains("/portal/tickets/" + n);
        Mockito.clearInvocations(mailer);
        mail.run(Instant.now().plusSeconds(180));
        Mockito.verifyNoInteractions(mailer);
    }

    @Test void theRequestersOwnCommentsAreNotMailedBack() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"subject\":\"모니터 깜빡임\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        mail.run(Instant.now());
        Mockito.clearInvocations(mailer);
        f.send(requester, post("/api/v1/portal/tickets/" + n + "/comments"), "{\"bodyHtml\":\"<p>사진 첨부합니다</p>\"}").andExpect(status().isOk());
        mail.run(Instant.now().plusSeconds(120));
        Mockito.verifyNoInteractions(mailer);
    }

    @Test void aMergedStatusChangeStillReachesTheRequesterOnceItHasSettled() throws Exception {
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
        db.update(JOURNALS).set(JOURNALS.CREATED_AT, now.minusMinutes(2)).set(JOURNALS.UPDATED_AT, now.minusMinutes(2)).where(JOURNALS.ID.eq(latest)).execute();
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, now.minusMinutes(1)).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId)).execute();
        lock = f.body(f.send(admin, get("/api/v1/work-packages/" + n), "").andReturn()).get("lockVersion").asInt();
        f.send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + lock + ",\"subject\":\"메일 안 옴 (재확인)\"}").andExpect(status().isOk());
        assertThat(db.fetchCount(JOURNALS, JOURNALS.ID.eq(latest))).isEqualTo(1); // merged, not a new entry
        mail.run(Instant.now());                    // the merge is younger than a minute: wait
        Mockito.verifyNoInteractions(mailer);
        mail.run(Instant.now().plusSeconds(120));
        var body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mailer).send(eq(ItsmFixture.REQUESTER), anyString(), body.capture());
        assertThat(body.getValue()).contains("상태").doesNotContain("재확인");
    }
}

package com.openpms.webhook;

import static com.openpms.db.Tables.API_TOKENS;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WEBHOOKS;
import static com.openpms.db.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** 웹훅: events reach the receiver signed, internal comments never do, failures are tried again and then given up. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class WebhooksIT {
    record Received(String path, Map<String, List<String>> headers, String body) {}

    static HttpServer receiver;
    static final List<Received> got = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", ex -> {
            got.add(new Received(ex.getRequestURI().getPath(), ex.getRequestHeaders(), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int code = ex.getRequestURI().getPath().startsWith("/fail") ? 500 : 204;
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stop() {
        receiver.stop(0);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;
    @Autowired
    Webhooks webhooks;
    @Autowired
    com.openpms.jobs.Housekeeping housekeeping;

    MockMvc mvc;
    MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = login("seoyeon.kim@openpms.local");
        got.clear();
    }

    @AfterEach
    void cleanUp() {
        db.deleteFrom(WEBHOOKS).execute();
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String url(String path) {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + path;
    }

    @Test
    void eventsArriveSignedAndInternalCommentsStayHome() throws Exception {
        var project = db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq("web-platform")).fetchOne(PROJECTS.ID);
        send(login("haneul.lee@openpms.local"), post("/api/v1/admin/webhooks"), "{\"name\":\"x\",\"url\":\"" + url("/hook") + "\",\"events\":[\"work_package.created\"]}")
                .andExpect(status().isForbidden());
        send(admin, post("/api/v1/admin/webhooks"), "{\"name\":\"x\",\"url\":\"ftp://example\",\"events\":[\"work_package.created\"]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("url"));
        send(admin, post("/api/v1/admin/webhooks"), "{\"name\":\"x\",\"url\":\"http://169.254.169.254/latest\",\"events\":[\"work_package.created\"]}")
                .andExpect(status().isUnprocessableContent());
        var made = json.readTree(send(admin, post("/api/v1/admin/webhooks"), "{\"name\":\"배포 알림\",\"url\":\"" + url("/hook")
                + "\",\"events\":[\"work_package.created\",\"work_package.commented\"],\"projectIds\":[\"" + project + "\"]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var secret = made.get("secret").asString();
        assertThat(secret).startsWith("whsec_");
        mvc.perform(get("/api/v1/admin/webhooks").session(admin)).andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].secretHint").value("…" + secret.substring(secret.length() - 4)));

        var type = json.readTree(mvc.perform(get("/api/v1/work-packages/3").session(admin)).andReturn().getResponse().getContentAsString()).get("type").get("id").asString();
        var wp = json.readTree(send(admin, post("/api/v1/projects/web-platform/work-packages"), "{\"subject\":\"웹훅으로 알릴 작업\",\"typeId\":\"" + type + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long number = wp.get("number").asLong();
        try {
            // not sent before the job runs; another project's item would not be queued at all
            assertThat(got).isEmpty();
            assertThat(webhooks.deliver(Instant.now())).isEqualTo(1);
            assertThat(got).hasSize(1);
            var r = got.get(0);
            assertThat(r.headers().get("X-openpms-event")).containsExactly("work_package.created");
            assertThat(r.headers().get("X-openpms-signature")).containsExactly(Webhooks.sign(secret, r.body()));
            assertThat(json.readTree(r.body()).get("workPackage").get("number").asLong()).isEqualTo(number);

            db.update(PROJECTS).set(PROJECTS.INTERNAL_COMMENTS, true).where(PROJECTS.ID.eq(project)).execute();
            send(admin, post("/api/v1/work-packages/" + number + "/comments"), "{\"bodyHtml\":\"<p>내부 메모</p>\",\"internal\":true}").andExpect(status().isCreated());
            send(admin, post("/api/v1/work-packages/" + number + "/comments"), "{\"bodyHtml\":\"<p>모두에게 알림</p>\"}").andExpect(status().isCreated());
            webhooks.deliver(Instant.now());
            assertThat(got).hasSize(2);
            assertThat(got.get(1).body()).contains("모두에게 알림").doesNotContain("내부 메모");

            // a receiver that fails: tried again later, then given up
            var failing = json.readTree(send(admin, post("/api/v1/admin/webhooks"), "{\"name\":\"고장\",\"url\":\"" + url("/fail") + "\",\"events\":[\"work_package.commented\"]}")
                    .andReturn().getResponse().getContentAsString()).get("id").asString();
            send(admin, post("/api/v1/work-packages/" + number + "/comments"), "{\"bodyHtml\":\"<p>또 하나</p>\"}").andExpect(status().isCreated());
            Instant now = Instant.now();
            webhooks.deliver(now);
            var d = db.selectFrom(WEBHOOK_DELIVERIES).where(WEBHOOK_DELIVERIES.WEBHOOK_ID.eq(java.util.UUID.fromString(failing))).fetchOne();
            assertThat(d.getStatus()).isEqualTo("pending");
            assertThat(d.getResponseStatus()).isEqualTo(500);
            assertThat(d.getNextAttemptAt().toInstant()).isAfter(now);
            for (int i = 0; i < 4; i++) webhooks.deliver(Instant.now().plusSeconds(60L * 60 * 24));
            d.refresh();
            assertThat(d.getStatus()).isEqualTo("failed");
            assertThat(d.getAttempts()).isEqualTo(5);
            mvc.perform(get("/api/v1/admin/webhooks/" + failing + "/deliveries").session(admin)).andExpect(jsonPath("$[0].status").value("failed"));

            // 테스트 전송: right away
            send(admin, post("/api/v1/admin/webhooks/" + made.get("id").asString() + "/test"), "{}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.event").value("ping")).andExpect(jsonPath("$.status").value("succeeded"));
        } finally {
            db.update(PROJECTS).set(PROJECTS.INTERNAL_COMMENTS, false).where(PROJECTS.ID.eq(project)).execute();
            send(admin, delete("/api/v1/work-packages/" + number), "").andExpect(status().is2xxSuccessful());
        }
    }

    /** Queued payloads for an item: the event plus the change labels. */
    List<String> queued(long number) {
        var out = new java.util.ArrayList<String>();
        for (var d : db.selectFrom(WEBHOOK_DELIVERIES).orderBy(WEBHOOK_DELIVERIES.CREATED_AT).fetch()) {
            var body = json.readTree(d.getPayload().data());
            if (!body.has("workPackage") || body.get("workPackage").get("number").asLong() != number) continue;
            var labels = new StringBuilder(d.getEvent());
            if (body.has("changes")) for (var c : body.get("changes")) labels.append(" ").append(c.get("label").asString());
            out.add(labels.toString());
        }
        return out;
    }

    long make(String subject, String type, String start, String due) throws Exception {
        return json.readTree(send(admin, post("/api/v1/projects/web-platform/work-packages"),
                "{\"subject\":\"" + subject + "\",\"typeId\":\"" + type + "\",\"startDate\":\"" + start + "\",\"dueDate\":\"" + due + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("number").asLong();
    }

    int lockVersion(long number) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/work-packages/" + number).session(admin)).andReturn().getResponse().getContentAsString()).get("lockVersion").asInt();
    }

    @Test
    void movesScheduleShiftsAttachmentsAndProjectCopiesAreEventsToo() throws Exception {
        send(admin, post("/api/v1/admin/webhooks"), "{\"name\":\"모든 변경\",\"url\":\"" + url("/hook") + "\",\"events\":[\"work_package.created\",\"work_package.updated\"]}")
                .andExpect(status().isCreated());
        var type = json.readTree(mvc.perform(get("/api/v1/work-packages/11").session(admin)).andReturn().getResponse().getContentAsString()).get("type").get("id").asString();
        long a = make("선행 작업", type, "2027-03-01", "2027-03-05");
        long b = make("후행 작업", type, "2027-03-08", "2027-03-09");
        try {
            send(admin, patch("/api/v1/work-packages/" + b), "{\"lockVersion\":" + lockVersion(b) + ",\"scheduleManually\":false}").andExpect(status().isOk());
            send(admin, post("/api/v1/work-packages/" + a + "/relations"), "{\"type\":\"precedes\",\"toNumber\":" + b + "}").andExpect(status().isNoContent());
            db.deleteFrom(WEBHOOK_DELIVERIES).execute();
            // A's due date moves out: B follows, and B's shift is an event of its own
            send(admin, patch("/api/v1/work-packages/" + a), "{\"lockVersion\":" + lockVersion(a) + ",\"dueDate\":\"2027-03-12\"}").andExpect(status().isOk());
            assertThat(queued(a)).hasSize(1);
            assertThat(queued(a).get(0)).startsWith("work_package.updated").contains("완료일");
            assertThat(queued(b)).hasSize(1);
            assertThat(queued(b).get(0)).startsWith("work_package.updated").contains("시작일");

            // a file added and removed
            db.deleteFrom(WEBHOOK_DELIVERIES).execute();
            var file = new org.springframework.mock.web.MockMultipartFile("file", "회의록.txt", "text/plain", "내용".getBytes(StandardCharsets.UTF_8));
            var att = json.readTree(mvc.perform(multipart("/api/v1/work-packages/" + a + "/attachments").file(file).session(admin).with(csrf()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
            mvc.perform(delete("/api/v1/attachments/" + att).session(admin).with(csrf())).andExpect(status().isNoContent());
            assertThat(queued(a)).containsExactly("work_package.updated 첨부 파일", "work_package.updated 첨부 파일");

            // moved to another project
            db.deleteFrom(WEBHOOK_DELIVERIES).execute();
            send(admin, post("/api/v1/work-packages/" + a + "/move"), "{\"targetProject\":\"mobile-app\"}").andExpect(status().isOk());
            assertThat(queued(a)).isNotEmpty();
            assertThat(queued(a).get(0)).startsWith("work_package.updated").contains("프로젝트");

            // a project copy: each copied item is created
            db.deleteFrom(WEBHOOK_DELIVERIES).execute();
            send(admin, post("/api/v1/projects/web-platform/copy"), "{\"name\":\"웹훅 복사 확인\",\"identifier\":\"webhook-copy\",\"copy\":[\"workPackages\"]}")
                    .andExpect(status().isCreated());
            var copied = db.fetchCount(com.openpms.db.Tables.WORK_PACKAGES, com.openpms.db.Tables.WORK_PACKAGES.PROJECT_ID.eq(
                    db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq("webhook-copy")).fetchOne(PROJECTS.ID)));
            assertThat(copied).isPositive();
            assertThat(db.fetchCount(WEBHOOK_DELIVERIES, WEBHOOK_DELIVERIES.EVENT.eq("work_package.created"))).isEqualTo(copied);
        } finally {
            if (db.fetchExists(PROJECTS, PROJECTS.IDENTIFIER.eq("webhook-copy")))
                send(admin, delete("/api/v1/projects/webhook-copy"), "{\"confirmIdentifier\":\"webhook-copy\"}").andExpect(status().isNoContent());
            send(admin, delete("/api/v1/work-packages/" + b), "").andExpect(status().is2xxSuccessful());
            send(admin, delete("/api/v1/work-packages/" + a), "").andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void privateAddressesAreRefusedWhenTheOperatorSaysSo() {
        var open = new Webhooks(db, json, null, false);
        assertThat(open.refused("127.0.0.1")).isNull();
        assertThat(open.refused("169.254.169.254")).isEqualTo(Webhooks.LINK_LOCAL);
        assertThat(open.refused("[fe80::1]")).isEqualTo(Webhooks.LINK_LOCAL);
        assertThat(open.refused("metadata.google.internal")).isEqualTo(Webhooks.LINK_LOCAL);
        var closed = new Webhooks(db, json, null, true);
        for (var host : List.of("127.0.0.1", "10.1.2.3", "172.20.0.5", "192.168.1.10", "100.64.0.1", "[::1]", "[fd00::5]", "0.0.0.0", "localhost"))
            assertThat(closed.refused(host)).as(host).isNotNull();
        assertThat(closed.refused("8.8.8.8")).isNull();
        assertThat(closed.refused("172.32.0.1")).isNull();
    }

    @Test
    void oldDeliveriesAndSpentTokensAreCleanedUp() throws Exception {
        var hook = json.readTree(send(admin, post("/api/v1/admin/webhooks"), "{\"name\":\"정리\",\"url\":\"" + url("/hook") + "\",\"events\":[\"work_package.created\"]}")
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        var id = java.util.UUID.fromString(hook);
        var old = java.time.OffsetDateTime.now().minusDays(31);
        for (var st : List.of("succeeded", "failed", "pending"))
            db.insertInto(WEBHOOK_DELIVERIES).set(WEBHOOK_DELIVERIES.WEBHOOK_ID, id).set(WEBHOOK_DELIVERIES.EVENT, "ping")
                    .set(WEBHOOK_DELIVERIES.PAYLOAD, org.jooq.JSONB.valueOf("{}")).set(WEBHOOK_DELIVERIES.STATUS, st).set(WEBHOOK_DELIVERIES.CREATED_AT, old).execute();
        db.insertInto(WEBHOOK_DELIVERIES).set(WEBHOOK_DELIVERIES.WEBHOOK_ID, id).set(WEBHOOK_DELIVERIES.EVENT, "ping")
                .set(WEBHOOK_DELIVERIES.PAYLOAD, org.jooq.JSONB.valueOf("{}")).set(WEBHOOK_DELIVERIES.STATUS, "succeeded").execute();
        var me = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("seoyeon.kim@openpms.local")).fetchOne(USERS.ID);
        try {
            db.insertInto(API_TOKENS).set(API_TOKENS.USER_ID, me).set(API_TOKENS.NAME, "오래 전에 폐기").set(API_TOKENS.TOKEN_HASH, new byte[] {1})
                    .set(API_TOKENS.SCOPE, "read").set(API_TOKENS.REVOKED_AT, old).execute();
            db.insertInto(API_TOKENS).set(API_TOKENS.USER_ID, me).set(API_TOKENS.NAME, "어제 폐기").set(API_TOKENS.TOKEN_HASH, new byte[] {2})
                    .set(API_TOKENS.SCOPE, "read").set(API_TOKENS.REVOKED_AT, java.time.OffsetDateTime.now().minusDays(1)).execute();
            housekeeping.tokens(Instant.now());
            // done and older than 30 days: gone; still pending or recent: kept
            assertThat(db.select(WEBHOOK_DELIVERIES.STATUS).from(WEBHOOK_DELIVERIES).where(WEBHOOK_DELIVERIES.WEBHOOK_ID.eq(id)).fetch(WEBHOOK_DELIVERIES.STATUS))
                    .containsExactlyInAnyOrder("pending", "succeeded");
            assertThat(db.select(API_TOKENS.NAME).from(API_TOKENS).where(API_TOKENS.USER_ID.eq(me)).fetch(API_TOKENS.NAME)).containsExactly("어제 폐기");
        } finally {
            db.deleteFrom(API_TOKENS).execute();
        }
    }
}

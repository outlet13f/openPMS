package com.openpms.webhook;

import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.WEBHOOKS;
import static com.openpms.db.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}

package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdHookIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    MockMvc mvc;
    CicdFixture f;
    MockHttpSession admin;

    @BeforeEach void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = new CicdFixture(mvc);
        admin = f.login(CicdFixture.ADMIN);
    }

    private static byte[] resource(String name) throws Exception {
        return new ClassPathResource("cicd/" + name).getContentAsByteArray();
    }

    private static String sign(byte[] body, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private MockHttpServletRequestBuilder github(String key, byte[] body, String signature) {
        return post("/api/v1/hooks/cicd/" + key).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-GitHub-Event", "push").header("X-Hub-Signature-256", signature);
    }

    private UUID attach(String connectionId, String externalId) {
        var project = db.select(com.openpms.db.Tables.PROJECTS.ID).from(com.openpms.db.Tables.PROJECTS).limit(1).fetchOne().value1();
        return db.insertInto(CICD_REPOSITORIES)
                .set(CICD_REPOSITORIES.CONNECTION_ID, UUID.fromString(connectionId)).set(CICD_REPOSITORIES.EXTERNAL_ID, externalId)
                .set(CICD_REPOSITORIES.FULL_PATH, "openpms-test/web").set(CICD_REPOSITORIES.WEB_URL, "https://github.com/openpms-test/web")
                .set(CICD_REPOSITORIES.PROJECT_ID, project).returning(CICD_REPOSITORIES.ID).fetchOne().getId();
    }

    @Test void signedGithubPushIsAccepted() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var repo = attach(c[0], "9001");
        var body = resource("github-push.json");
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
        var rows = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(c[0]))).fetch();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("pending");
        assertThat(rows.get(0).getVia()).isEqualTo("webhook");
        assertThat(rows.get(0).getKind()).isEqualTo("commit");
        assertThat(rows.get(0).getRepositoryId()).isEqualTo(repo);
        assertThat(rows.get(0).getPayload().data()).contains("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d");
    }

    @Test void eventOfUnattachedRepositoryIsStoredWithoutRepository() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var body = resource("github-push.json");
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
        assertThat(db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(c[0]))).fetchOne().getRepositoryId()).isNull();
    }

    @Test void sameDeliveryTwiceStoresOnce() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var body = resource("github-push.json");
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(jsonPath("$.accepted").value(1));
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(0));
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(c[0])))).isEqualTo(1);
    }

    @Test void badSignatureAndUnknownKeyLookTheSame() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var body = resource("github-push.json");
        var wrong = mvc.perform(github(c[2], body, sign(body, "not-the-secret"))).andExpect(status().isNotFound()).andReturn();
        var unknown = mvc.perform(github("nosuchkey", body, sign(body, c[1]))).andExpect(status().isNotFound()).andReturn();
        var missing = mvc.perform(post("/api/v1/hooks/cicd/" + c[2]).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-GitHub-Event", "push")).andExpect(status().isNotFound()).andReturn();
        var a = wrong.getResponse().getContentAsString();
        assertThat(a).isNotEmpty().isEqualTo(unknown.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(c[0])))).isZero();
    }

    @Test void inactiveConnectionIsNotFound() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var body = resource("github-push.json");
        f.send(admin, patch("/api/v1/admin/cicd/connections/" + c[0]), "{\"active\":false}").andExpect(status().isOk());
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(status().isNotFound());
    }

    @Test void tooLargeIs413() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var body = new byte[1_048_576 + 1];
        java.util.Arrays.fill(body, (byte) ' ');
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(status().isPayloadTooLarge());
    }

    @Test void noCsrfOrSessionNeeded() throws Exception {
        var c = f.newConnection(admin, "github", "https://api.github.com", null);
        var body = resource("github-push.json");
        // no .with(csrf()) and no session
        mvc.perform(github(c[2], body, sign(body, c[1]))).andExpect(status().isAccepted());
        // every other /api POST without a session or CSRF token is refused
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        // only POST on exactly one segment is open: GET and deeper paths are not
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/hooks/cicd/" + c[2]))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/hooks/cicd/" + c[2] + "/x").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test void gitlabTokenHeader() throws Exception {
        var c = f.newConnection(admin, "gitlab", "https://gitlab.example.com", null);
        var body = resource("gitlab-push.json");
        mvc.perform(post("/api/v1/hooks/cicd/" + c[2]).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Gitlab-Token", c[1]).header("X-Gitlab-Event", "Push Hook"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));
        mvc.perform(post("/api/v1/hooks/cicd/" + c[2]).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Gitlab-Token", "wrong").header("X-Gitlab-Event", "Push Hook")).andExpect(status().isNotFound());
    }
}

package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static com.openpms.db.Tables.API_TOKENS;
import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.IDEMPOTENCY_KEYS;
import static com.openpms.db.Tables.SETTINGS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdConnectionsIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    CicdFixture f;
    MockHttpSession admin, junho;
    FakeGitServer git;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        git = new FakeGitServer();
    }

    @AfterEach void tearDown() throws Exception {
        git.close();
        // the database is shared: give the delegated role back so the other tests see an ordinary 박준호
        f.send(admin, put("/api/v1/admin/users/" + CicdFixture.JUNHO + "/global-roles"), "{\"roleIds\":[]}").andExpect(status().isOk());
    }

    @Test void ordinaryUsersCannotManageConnections() throws Exception {
        f.send(junho, get("/api/v1/admin/cicd/connections"), "").andExpect(status().isForbidden());
        f.send(junho, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"x\",\"baseUrl\":\"https://g.local\"}")
                .andExpect(status().isForbidden());
    }

    @Test void delegatedAdminNeverSeesSecrets() throws Exception {
        f.grantGlobal(admin, CicdFixture.JUNHO, CicdFixture.ROLE_CICD_ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        var created = f.send(junho, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"gitlab\",\"name\":\"사내 GitLab " + UUID.randomUUID() + "\",\"baseUrl\":\"" + git.baseUrl() + "/\",\"apiToken\":\"glpat-SECRETTOKEN9876\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var secret = f.json.readTree(created).get("webhookSecret").asString();
        assertThat(secret).hasSizeGreaterThan(30);
        assertThat(created).doesNotContain("glpat-SECRETTOKEN9876").contains("\"tokenHint\":\"9876\"");
        var list = f.send(junho, get("/api/v1/admin/cicd/connections"), "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("glpat-SECRETTOKEN9876").doesNotContain(secret);
        assertThat(f.json.readTree(created).at("/connection/baseUrl").asString()).doesNotEndWith("/");
    }

    @Test void checkAnswersOnlyOkAndStatus() throws Exception {
        git.respond("/api/v4/user", 500, "<html>internal host db01.corp password=hunter2</html>");
        var id = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-x1234")[0];
        var body = f.send(admin, post("/api/v1/admin/cicd/connections/" + id + "/check"), "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"ok\":false,\"status\":500}");
        assertThat(git.requests().get(0).headers().get("private-token")).isEqualTo("glpat-x1234");
    }

    @Test void rotatingTheSecretReturnsANewOneOnce() throws Exception {
        var c = f.newConnection(admin, "github", git.baseUrl(), null);
        var next = f.body(f.send(admin, post("/api/v1/admin/cicd/connections/" + c[0] + "/secret"), "").andExpect(status().isOk()).andReturn())
                .get("webhookSecret").asString();
        assertThat(next).isNotEqualTo(c[1]);
    }

    @Test void changingTheAddressDropsTheStoredToken() throws Exception {
        var id = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-ABCD1234")[0];
        try (var evil = new FakeGitServer()) {
            var patched = f.body(f.send(admin, patch("/api/v1/admin/cicd/connections/" + id), "{\"baseUrl\":\"" + evil.baseUrl() + "\"}")
                    .andExpect(status().isOk()).andReturn());
            assertThat(patched.get("tokenHint").isNull()).isTrue();
            var check = f.send(admin, post("/api/v1/admin/cicd/connections/" + id + "/check"), "").andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(check).isEqualTo("{\"ok\":false,\"status\":0}");
            assertThat(evil.requests()).isEmpty();
            // a new token in the same request is kept
            try (var other = new FakeGitServer()) {
                var both = f.body(f.send(admin, patch("/api/v1/admin/cicd/connections/" + id),
                        "{\"baseUrl\":\"" + other.baseUrl() + "\",\"apiToken\":\"glpat-NEWTOKEN5678\"}").andExpect(status().isOk()).andReturn());
                assertThat(both.get("tokenHint").asString()).isEqualTo("5678");
            }
        }
    }

    @Test void sameAddressKeepsTheTokenAndEmptyTokenClearsIt() throws Exception {
        var id = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-ABCD1234")[0];
        var same = f.body(f.send(admin, patch("/api/v1/admin/cicd/connections/" + id), "{\"baseUrl\":\"" + git.baseUrl() + "/\"}")
                .andExpect(status().isOk()).andReturn());
        assertThat(same.get("tokenHint").asString()).isEqualTo("1234");
        var cleared = f.body(f.send(admin, patch("/api/v1/admin/cicd/connections/" + id), "{\"apiToken\":\"\"}").andExpect(status().isOk()).andReturn());
        assertThat(cleared.get("tokenHint").isNull()).isTrue();
    }

    @Test void ordinaryUsersCannotChangeOrDeleteConnections() throws Exception {
        var id = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        var url = "/api/v1/admin/cicd/connections/" + id;
        f.send(junho, patch(url), "{\"active\":false}").andExpect(status().isForbidden());
        f.send(junho, post(url + "/secret"), "").andExpect(status().isForbidden());
        f.send(junho, post(url + "/check"), "").andExpect(status().isForbidden());
        f.send(junho, delete(url), "").andExpect(status().isForbidden());
    }

    @Test void renameToAnExistingNameConflicts() throws Exception {
        var a = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        var b = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        var existing = db.select(CICD_CONNECTIONS.NAME).from(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ID.eq(UUID.fromString(a))).fetchOne(0, String.class);
        f.send(admin, patch("/api/v1/admin/cicd/connections/" + b), "{\"name\":\"" + existing + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("같은 이름의 연동이 있습니다."));
    }

    @Test void deleteWorksUnlessRepositoriesAreAttached() throws Exception {
        var free = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        f.send(admin, delete("/api/v1/admin/cicd/connections/" + free), "").andExpect(status().isNoContent());
        var busy = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        var project = f.newProject(admin, "work_packages", "cicd");
        var projectId = db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(project)).fetchOne(0, UUID.class);
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.CONNECTION_ID, UUID.fromString(busy)).set(CICD_REPOSITORIES.EXTERNAL_ID, "1")
                .set(CICD_REPOSITORIES.FULL_PATH, "g/r").set(CICD_REPOSITORIES.WEB_URL, "https://g.local/g/r").set(CICD_REPOSITORIES.PROJECT_ID, projectId).execute();
        f.send(admin, delete("/api/v1/admin/cicd/connections/" + busy), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value(KEEP));
    }

    static final String KEEP = "기록이 남은 저장소(뗀 저장소 포함)가 있는 연동은 지울 수 없습니다. 대신 끄세요.";

    @Test void deleteRefusedWhileDetachedRepositoriesKeepHistory() throws Exception {
        var id = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        var project = f.newProject(admin, "work_packages", "cicd");
        var projectId = db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(project)).fetchOne(0, UUID.class);
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.CONNECTION_ID, UUID.fromString(id)).set(CICD_REPOSITORIES.EXTERNAL_ID, "2")
                .set(CICD_REPOSITORIES.FULL_PATH, "g/old").set(CICD_REPOSITORIES.WEB_URL, "https://g.local/g/old").set(CICD_REPOSITORIES.PROJECT_ID, projectId)
                .set(CICD_REPOSITORIES.DETACHED_AT, java.time.OffsetDateTime.now()).execute();
        f.send(admin, delete("/api/v1/admin/cicd/connections/" + id), "").andExpect(status().isConflict()).andExpect(jsonPath("$.title").value(KEEP));
        var list = f.body(f.send(admin, get("/api/v1/admin/cicd/connections"), "").andExpect(status().isOk()).andReturn());
        for (var c : list) if (c.get("id").asString().equals(id)) assertThat(c.get("repositoryCount").asInt()).isZero();
    }

    @Test void jenkinsConnectionHasNoToken() throws Exception {
        var name = "Jenkins " + UUID.randomUUID();
        var created = f.body(f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"jenkins\",\"name\":\"" + name + "\",\"baseUrl\":\"https://jenkins.local/\"}").andExpect(status().isCreated()).andReturn());
        assertThat(created.at("/connection/toolUrl").asString()).isEqualTo("https://jenkins.local");
        assertThat(created.at("/connection/baseUrl").asString()).isEqualTo("https://jenkins.local");
        assertThat(created.at("/connection/tokenHint").isNull()).isTrue();
        var id = created.at("/connection/id").asString();
        var refused = "Jenkins·Argo CD 연동에는 API 토큰을 쓰지 않습니다.";
        f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"jenkins\",\"name\":\"" + name + "-2\",\"baseUrl\":\"https://jenkins.local\",\"apiToken\":\"abc\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(refused));
        // a blank token counts as absent (F14), also on PATCH
        f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"argocd\",\"name\":\"" + name + "-3\",\"baseUrl\":\"https://argo.local\",\"apiToken\":\"  \"}").andExpect(status().isCreated());
        f.send(admin, patch("/api/v1/admin/cicd/connections/" + id), "{\"apiToken\":\"abc\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(refused));
        f.send(admin, patch("/api/v1/admin/cicd/connections/" + id), "{\"apiToken\":\"\"}").andExpect(status().isOk());
        // the tool address follows the base address
        var moved = f.body(f.send(admin, patch("/api/v1/admin/cicd/connections/" + id), "{\"baseUrl\":\"https://ci.local/\"}").andExpect(status().isOk()).andReturn());
        assertThat(moved.get("toolUrl").asString()).isEqualTo("https://ci.local");
        assertThat(db.select(CICD_CONNECTIONS.TOOL_URL).from(CICD_CONNECTIONS).where(CICD_CONNECTIONS.ID.eq(UUID.fromString(id))).fetchOne(0, String.class))
                .isEqualTo("https://ci.local");
        assertThat(f.send(admin, post("/api/v1/admin/cicd/connections/" + id + "/check"), "").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).isEqualTo("{\"ok\":false,\"status\":0}");
    }

    @Test void gitlabKeepsItsTokenAndIgnoresTheTagRule() throws Exception {
        var created = f.body(f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"gitlab\",\"name\":\"GL " + UUID.randomUUID() + "\",\"baseUrl\":\"" + git.baseUrl() + "\",\"apiToken\":\"glpat-ZZZZ1111\",\"imageTagPattern\":\"x\"}")
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.at("/connection/tokenHint").asString()).isEqualTo("1111");
        assertThat(created.at("/connection/toolUrl").isNull()).isTrue();
        assertThat(created.at("/connection/imageTagPattern").isNull()).isTrue();
    }

    @Test void imageTagPatternIsValidated() throws Exception {
        var url = "/api/v1/admin/cicd/connections";
        var name = "Argo " + UUID.randomUUID();
        f.send(admin, post(url), "{\"kind\":\"argocd\",\"name\":\"" + name + "\",\"baseUrl\":\"https://argo.local\",\"imageTagPattern\":\"([0-9\"}")
                .andExpect(status().isUnprocessableEntity());
        var created = f.body(f.send(admin, post(url), "{\"kind\":\"argocd\",\"name\":\"" + name + "\",\"baseUrl\":\"https://argo.local\",\"imageTagPattern\":\"-g([0-9a-f]{7,40})$\"}")
                .andExpect(status().isCreated()).andReturn());
        var id = created.at("/connection/id").asString();
        assertThat(created.at("/connection/imageTagPattern").asString()).isEqualTo("-g([0-9a-f]{7,40})$");
        var shown = false;
        for (var c : f.body(f.send(admin, get(url), "").andExpect(status().isOk()).andReturn()))
            if (c.get("id").asString().equals(id)) shown = "-g([0-9a-f]{7,40})$".equals(c.get("imageTagPattern").asString());
        assertThat(shown).isTrue();
        f.send(admin, patch(url + "/" + id), "{\"imageTagPattern\":\"([0-9\"}").andExpect(status().isUnprocessableEntity());
        var cleared = f.body(f.send(admin, patch(url + "/" + id), "{\"imageTagPattern\":\"\"}").andExpect(status().isOk()).andReturn());
        assertThat(cleared.get("imageTagPattern").isNull()).isTrue();
    }

    @Test void templatesForJenkinsAndArgo() throws Exception {
        var jenkins = f.body(f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"jenkins\",\"name\":\"J " + UUID.randomUUID() + "\",\"baseUrl\":\"https://jenkins.local\"}").andExpect(status().isCreated()).andReturn());
        var id = jenkins.at("/connection/id").asString();
        var hook = jenkins.at("/connection/webhookUrl").asString();
        var t = f.body(f.send(admin, get("/api/v1/admin/cicd/connections/" + id + "/templates"), "").andExpect(status().isOk()).andReturn());
        assertThat(t.get("kind").asString()).isEqualTo("jenkins");
        assertThat(t.get("jenkinsfile").asString()).contains(hook).doesNotContain(jenkins.get("webhookSecret").asString());
        assertThat(t.get("argocd").isNull()).isTrue();
        var argo = f.body(f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"argocd\",\"name\":\"A " + UUID.randomUUID() + "\",\"baseUrl\":\"https://argo.local\"}").andExpect(status().isCreated()).andReturn());
        var at = f.body(f.send(admin, get("/api/v1/admin/cicd/connections/" + argo.at("/connection/id").asString() + "/templates"), "").andExpect(status().isOk()).andReturn());
        assertThat(at.get("argocd").asString()).contains(argo.at("/connection/webhookUrl").asString());
        assertThat(at.get("jenkinsfile").isNull()).isTrue();
        var gl = f.newConnection(admin, "gitlab", git.baseUrl(), null)[0];
        var none = f.body(f.send(admin, get("/api/v1/admin/cicd/connections/" + gl + "/templates"), "").andExpect(status().isOk()).andReturn());
        assertThat(none.get("jenkinsfile").isNull()).isTrue();
        assertThat(none.get("argocd").isNull()).isTrue();
        f.send(junho, get("/api/v1/admin/cicd/connections/" + id + "/templates"), "").andExpect(status().isForbidden());
    }

    @Test void delegatedAdminSeesNoSecretInTemplates() throws Exception {
        var argo = f.body(f.send(admin, post("/api/v1/admin/cicd/connections"),
                "{\"kind\":\"argocd\",\"name\":\"A " + UUID.randomUUID() + "\",\"baseUrl\":\"https://argo.local\"}").andExpect(status().isCreated()).andReturn());
        f.grantGlobal(admin, CicdFixture.JUNHO, CicdFixture.ROLE_CICD_ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        var body = f.send(junho, get("/api/v1/admin/cicd/connections/" + argo.at("/connection/id").asString() + "/templates"), "")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(argo.get("webhookSecret").asString()).contains("$openpms-webhook-secret");
    }

    @Test void imageTagTest() throws Exception {
        var url = "/api/v1/admin/cicd/image-tag-test";
        assertThat(f.send(admin, post(url), "{\"pattern\":null,\"image\":\"a/b:1a2b3c4d\"}").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).isEqualTo("{\"sha\":\"1a2b3c4d\"}");
        assertThat(f.send(admin, post(url), "{\"image\":\"a/b:latest\"}").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).isEqualTo("{\"sha\":null}");
        f.send(admin, post(url), "{\"pattern\":\"([0-9\",\"image\":\"a/b:1\"}").andExpect(status().isUnprocessableEntity());
        f.send(junho, post(url), "{\"image\":\"a/b:1a2b3c4d\"}").andExpect(status().isForbidden());
    }

    @Test void answersWithTheWebhookSecretAreNotKeptForIdempotencyRetries() throws Exception {
        var createKey = UUID.randomUUID().toString();
        var created = f.body(f.mvc.perform(post("/api/v1/admin/cicd/connections").session(admin).with(csrf()).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"github\",\"name\":\"멱등 " + UUID.randomUUID() + "\",\"baseUrl\":\"" + git.baseUrl() + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.get("webhookSecret").asString()).hasSizeGreaterThan(30);
        assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq(createKey))).isZero();
        var rotateKey = UUID.randomUUID().toString();
        var id = created.at("/connection/id").asString();
        var rotated = f.body(f.mvc.perform(post("/api/v1/admin/cicd/connections/" + id + "/secret").session(admin).with(csrf())
                        .header("Idempotency-Key", rotateKey)).andExpect(status().isOk()).andReturn());
        assertThat(rotated.get("webhookSecret").asString()).hasSizeGreaterThan(30).isNotEqualTo(created.get("webhookSecret").asString());
        assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq(rotateKey))).isZero();
    }

    @Test void anApiTokenCannotReachTheConnectionSettings() throws Exception {
        db.deleteFrom(SETTINGS).where(SETTINGS.KEY.eq("api.tokens_enabled")).execute(); // tokens on (the default)
        var id = f.newConnection(admin, "github", git.baseUrl(), null)[0];
        var project = f.newProject(admin, "work_packages", "cicd");
        var secret = f.body(f.send(admin, post("/api/v1/me/api-tokens"), "{\"name\":\"CI/CD 토큰 시험\",\"scope\":\"write\",\"expiresInDays\":30}")
                .andExpect(status().isCreated()).andReturn()).get("secret").asString();
        var refused = "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.";
        try {
            f.mvc.perform(get("/api/v1/admin/cicd/connections").header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            f.mvc.perform(post("/api/v1/admin/cicd/connections").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"github\",\"name\":\"토큰 " + UUID.randomUUID() + "\",\"baseUrl\":\"" + git.baseUrl() + "\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            f.mvc.perform(post("/api/v1/admin/cicd/connections/" + id + "/secret").header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            // percent-encoded: the same route for Spring
            f.mvc.perform(get(new URI("/api/v1/admin/%63icd/connections")).header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden());
            // the project side stays open to tokens
            f.mvc.perform(get("/api/v1/projects/" + project + "/cicd/repositories").header("Authorization", "Bearer " + secret))
                    .andExpect(status().isOk());
        } finally {
            // ApiTokensIT counts every token in force: leave none behind
            db.deleteFrom(API_TOKENS).where(API_TOKENS.NAME.eq("CI/CD 토큰 시험")).execute();
        }
    }

    @Test void namesWithControlCharactersAreRefused() throws Exception {
        for (var bad : new String[] {"a\\nb", "a\\rb", "a\\tb", "a\\u007fb"})
            f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"" + bad + "\",\"baseUrl\":\"https://x\"}")
                    .andExpect(status().isUnprocessableEntity());
    }

    @Test void badInputs() throws Exception {
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"svn\",\"name\":\"a\",\"baseUrl\":\"https://x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"https://u:p@x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"ftp://x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"https://x/?t=1\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"https://x/#f\"}").andExpect(status().isUnprocessableEntity());
    }
}

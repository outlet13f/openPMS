package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
                .andExpect(jsonPath("$.title").value("저장소가 붙어 있는 연동은 지울 수 없습니다. 먼저 저장소를 떼세요."));
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

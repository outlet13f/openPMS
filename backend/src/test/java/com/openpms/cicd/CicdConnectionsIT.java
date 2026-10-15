package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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

    @Test void badInputs() throws Exception {
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"svn\",\"name\":\"a\",\"baseUrl\":\"https://x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"https://u:p@x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"ftp://x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"https://x/?t=1\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post("/api/v1/admin/cicd/connections"), "{\"kind\":\"gitlab\",\"name\":\"a\",\"baseUrl\":\"https://x/#f\"}").andExpect(status().isUnprocessableEntity());
    }
}

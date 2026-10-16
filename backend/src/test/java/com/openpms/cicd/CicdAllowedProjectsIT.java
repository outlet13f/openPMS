package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import tools.jackson.databind.JsonNode;

/** Allowed projects per connection (V27): only projects on a connection's list see it, and only system administrators edit the list. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdAllowedProjectsIT {
    static final String DEV_CONNECTION = "fab25892-ec02-5f18-85b0-eebc81857f6b";
    static final String WEB_PLATFORM = "935daeb0-d1aa-57f4-88e1-0050b30413ac";
    static final String MOBILE_APP = "09e67323-1a1d-5ccd-854b-78e0628e38c0";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    CicdFixture f;
    MockHttpSession admin, junho;
    FakeGitServer git;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        git.respond("/api/v4/projects", 200, "cicd/gitlab-projects.json");
        git.respond("/api/v4/projects/4201", 200,
                "{\"id\":4201,\"path_with_namespace\":\"openpms-test/web\",\"web_url\":\"https://gitlab.example.com/openpms-test/web\"}");
    }

    @AfterEach void tearDown() throws Exception {
        git.close();
        // the database is shared: give the delegated role back so the other tests see an ordinary 박준호
        f.grantGlobalNone(admin, CicdFixture.JUNHO);
    }

    /** A project where 박준호 leads development. */
    private String leaderProject() throws Exception {
        var project = f.newProject(admin, "cicd");
        var members = f.body(f.send(admin, get("/api/v1/projects/" + project + "/members"), "").andExpect(status().isOk()).andReturn());
        for (var m : members)
            if (CicdFixture.JUNHO.toString().equals(m.at("/principal/id").asString()))
                f.send(admin, patch("/api/v1/members/" + m.get("id").asString()), "{\"roleIds\":[\"" + CicdFixture.ROLE_DEV_LEAD + "\"]}")
                        .andExpect(status().isOk());
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        return project;
    }

    private String attachBody(String connection) {
        return "{\"connectionId\":\"" + connection + "\",\"externalId\":\"4201\"}";
    }

    private JsonNode connectionAs(MockHttpSession s, String id) throws Exception {
        for (var c : f.body(f.send(s, get("/api/v1/admin/cicd/connections"), "").andExpect(status().isOk()).andReturn()))
            if (id.equals(c.get("id").asString())) return c;
        throw new AssertionError("connection not listed: " + id);
    }

    private org.springframework.test.web.servlet.ResultActions putProjects(MockHttpSession s, String connection, String... projectIds) throws Exception {
        var ids = new StringBuilder();
        for (var p : projectIds) ids.append(ids.length() == 0 ? "" : ",").append('"').append(p).append('"');
        return f.send(s, put("/api/v1/admin/cicd/connections/" + connection + "/projects"), "{\"projectIds\":[" + ids + "]}");
    }

    @Test void aProjectNotOnTheListDoesNotSeeTheConnection() throws Exception {
        var project = leaderProject();
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        var choices = f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections"), "").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(choices).doesNotContain(c[0]);
        // the same 404 as a connection that is off or missing: its existence is not revealed
        f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("연동을 찾을 수 없습니다."));
        f.send(junho, post("/api/v1/projects/" + project + "/cicd/repositories"), attachBody(c[0])).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("연동을 찾을 수 없습니다."));
        assertThat(git.requests()).isEmpty();

        f.allow(admin, c[0], project);
        assertThat(f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections"), "").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).contains(c[0]);
        f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "").andExpect(status().isOk());
        f.send(junho, post("/api/v1/projects/" + project + "/cicd/repositories"), attachBody(c[0])).andExpect(status().isCreated());
    }

    @Test void attachChecksTheListAgain() throws Exception {
        var project = leaderProject();
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        f.allow(admin, c[0], project);
        f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "").andExpect(status().isOk());
        // taken off the list between the search and the attach
        f.allow(admin, c[0]);
        f.send(junho, post("/api/v1/projects/" + project + "/cicd/repositories"), attachBody(c[0])).andExpect(status().isNotFound());
        assertThat(git.requests()).noneMatch(r -> r.path().equals("/api/v4/projects/4201"));
    }

    @Test void aNewConnectionStartsWithAnEmptyList() throws Exception {
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), null);
        var listed = connectionAs(admin, c[0]);
        assertThat(listed.get("projects")).isEmpty();
        assertThat(listed.get("hiddenProjectCount").asInt()).isZero();
    }

    @Test void adminSetsTheList() throws Exception {
        var a = f.newProject(admin, "cicd");
        var b = f.newProject(admin, "cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("openpms.audit");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        JsonNode both;
        try {
            both = f.allow(admin, c[0], a, b);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("cicd.connection.projects_changed ") && m.contains("id=" + c[0]) && m.endsWith("added=2 removed=0"));
        assertThat(both.get("projects")).hasSize(2);
        assertThat(both.get("hiddenProjectCount").asInt()).isZero();
        assertThat(both.get("projects").get(0).has("name")).isTrue();
        var onlyB = f.allow(admin, c[0], b);
        assertThat(onlyB.get("projects")).hasSize(1);
        assertThat(onlyB.at("/projects/0/id").asString()).isEqualTo(f.projectId(admin, b));
        assertThat(connectionAs(admin, c[0]).get("projects")).hasSize(1);
        // the same id twice is one row
        var bId = f.projectId(admin, b);
        putProjects(admin, c[0], bId, bId).andExpect(status().isOk());
        assertThat(db.fetchCount(db.selectFrom("cicd_connection_projects").where("connection_id = ?::uuid", c[0]))).isEqualTo(1);
    }

    @Test void unknownProjectsAndBadBodiesAreRefused() throws Exception {
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), null);
        putProjects(admin, c[0], UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("projectIds"));
        putProjects(admin, c[0], "not-a-uuid").andExpect(status().isUnprocessableEntity());
        f.send(admin, put("/api/v1/admin/cicd/connections/" + c[0] + "/projects"), "{\"projectIds\":\"x\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, put("/api/v1/admin/cicd/connections/" + c[0] + "/projects"), "{}").andExpect(status().isUnprocessableEntity());
        putProjects(admin, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertThat(connectionAs(admin, c[0]).get("projects")).isEmpty();
    }

    @Test void aProjectWithAttachedRepositoriesStaysOnTheList() throws Exception {
        var project = leaderProject();
        var other = f.newProject(admin, "cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        f.allow(admin, c[0], project, other);
        var repo = f.body(f.send(junho, post("/api/v1/projects/" + project + "/cicd/repositories"), attachBody(c[0]))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        putProjects(admin, c[0], f.projectId(admin, other)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("이 연동의 저장소가 붙어 있는 프로젝트는 뺄 수 없습니다. 먼저 저장소를 떼세요."));
        // nothing changed: both still on the list
        assertThat(connectionAs(admin, c[0]).get("projects")).hasSize(2);
        // removing another project works; detaching keeps working, and then the project can go
        f.allow(admin, c[0], project);
        f.send(junho, delete("/api/v1/projects/" + project + "/cicd/repositories/" + repo), "").andExpect(status().isNoContent());
        f.allow(admin, c[0]);
    }

    @Test void delegatedManagersCannotChangeTheListAndSeeOnlyTheirProjects() throws Exception {
        var mine = f.newProject(admin, "cicd"); // 박준호 is a member
        var hiddenIdentifier = "cd-" + UUID.randomUUID().toString().substring(0, 8);
        var hiddenName = "숨은 프로젝트 " + hiddenIdentifier;
        f.send(admin, post("/api/v1/projects"), "{\"name\":\"" + hiddenName + "\",\"identifier\":\"" + hiddenIdentifier + "\"}").andExpect(status().isCreated());
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), null);
        f.allow(admin, c[0], mine, hiddenIdentifier);

        f.grantGlobal(admin, CicdFixture.JUNHO, CicdFixture.ROLE_CICD_ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        putProjects(junho, c[0], f.projectId(admin, mine)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("허용 프로젝트는 시스템 관리자만 바꿀 수 있습니다."));
        var seen = connectionAs(junho, c[0]);
        assertThat(seen.get("projects")).hasSize(1);
        assertThat(seen.at("/projects/0/id").asString()).isEqualTo(f.projectId(admin, mine));
        assertThat(seen.get("hiddenProjectCount").asInt()).isEqualTo(1);
        var raw = f.send(junho, get("/api/v1/admin/cicd/connections"), "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(hiddenName).doesNotContain(hiddenIdentifier).doesNotContain(f.projectId(admin, hiddenIdentifier));
        // the administrator sees both
        var all = connectionAs(admin, c[0]);
        assertThat(all.get("projects")).hasSize(2);
        assertThat(all.get("hiddenProjectCount").asInt()).isZero();
        assertThat(db.fetchCount(db.selectFrom("cicd_connection_projects").where("connection_id = ?::uuid", c[0]))).isEqualTo(2);
    }

    @Test void deletingAProjectDropsItFromTheList() throws Exception {
        var project = f.newProject(admin, "cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), null);
        f.allow(admin, c[0], project);
        f.send(admin, delete("/api/v1/projects/" + project), "{\"confirmIdentifier\":\"" + project + "\"}").andExpect(status().isNoContent());
        assertThat(connectionAs(admin, c[0]).get("projects")).isEmpty();
        assertThat(db.fetchCount(db.selectFrom("cicd_connection_projects").where("connection_id = ?::uuid", c[0]))).isZero();
    }

    @Test void devDataAllowsTheProjectsWithRepositories() throws Exception {
        // by name, in Korean order
        var listed = connectionAs(admin, DEV_CONNECTION).get("projects");
        assertThat(listed).extracting(n -> n.get("name").asString()).containsExactly("모바일 앱 2.0", "웹 플랫폼 개편");
        // V27 fills the list from the attached repositories; on a fresh dev database V1011 does it for V1010's repositories
        var allowed = db.fetch("select project_id::text from cicd_connection_projects where connection_id = ?::uuid", DEV_CONNECTION)
                .getValues(0, String.class);
        assertThat(allowed).containsExactlyInAnyOrder(WEB_PLATFORM, MOBILE_APP);
    }
}

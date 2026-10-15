package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
class CicdRepositoriesIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    CicdFixture f;
    MockHttpSession admin, junho, minji, doyoon;
    FakeGitServer git;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        minji = f.login(CicdFixture.MINJI_EMAIL);
        doyoon = f.login("doyoon.han@openpms.local");
        git = new FakeGitServer();
        git.respond("/api/v4/projects", 200, "cicd/gitlab-projects.json");
    }

    @AfterEach void tearDown() {
        git.close();
    }

    /** A project where 박준호 leads development. */
    private String leaderProject(String... modules) throws Exception {
        var project = f.newProject(admin, modules);
        // newProject already made 박준호 a plain member; addMember skips existing members, so change the roles
        var members = f.body(f.send(admin, get("/api/v1/projects/" + project + "/members"), "").andExpect(status().isOk()).andReturn());
        for (var m : members)
            if (CicdFixture.JUNHO.toString().equals(m.at("/principal/id").asString()))
                f.send(admin, patch("/api/v1/members/" + m.get("id").asString()), "{\"roleIds\":[\"" + CicdFixture.ROLE_DEV_LEAD + "\"]}")
                        .andExpect(status().isOk());
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        return project;
    }

    private String attach(MockHttpSession s, String project, String connection, String externalId) throws Exception {
        return f.body(f.send(s, post("/api/v1/projects/" + project + "/cicd/repositories"),
                "{\"connectionId\":\"" + connection + "\",\"externalId\":\"" + externalId + "\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    @Test void leaderSearchesAndAttaches() throws Exception {
        var project = leaderProject("cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        var found = f.body(f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "")
                .andExpect(status().isOk()).andReturn());
        assertThat(found.get(0).get("fullPath").asString()).isEqualTo("openpms-test/web");
        assertThat(found.get(0).has("attachedProject")).isFalse();
        var conns = f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections"), "").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(conns).contains(c[0]).doesNotContain("baseUrl").doesNotContain("tokenHint");
        attach(junho, project, c[0], "4201");
        var list = f.body(f.send(junho, get("/api/v1/projects/" + project + "/cicd/repositories"), "").andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("fullPath").asString()).isEqualTo("openpms-test/web");
        assertThat(list.get(0).get("kind").asString()).isEqualTo("gitlab");
        var cursor = db.select(CICD_REPOSITORIES.POLL_CURSOR).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.EXTERNAL_ID.eq("4201"))
                .and(CICD_REPOSITORIES.CONNECTION_ID.eq(UUID.fromString(c[0]))).fetchOne(0, OffsetDateTime.class);
        assertThat(cursor).isBetween(OffsetDateTime.now().minusDays(7).minusMinutes(1), OffsetDateTime.now().minusDays(7).plusMinutes(1));
        // search shows where a repository is attached, by the caller's own project only
        var again = f.body(f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "")
                .andExpect(status().isOk()).andReturn());
        assertThat(again.get(0).get("attachedProject").asBoolean()).isTrue();
        f.send(junho, delete("/api/v1/projects/" + project + "/cicd/repositories/" + list.get(0).get("id").asString()), "")
                .andExpect(status().isNoContent());
        assertThat(db.fetchCount(CICD_REPOSITORIES, CICD_REPOSITORIES.CONNECTION_ID.eq(UUID.fromString(c[0])))).isZero();
    }

    @Test void membersAndObserversCanSeeButNotAttach() throws Exception {
        var project = f.newProject(admin, "cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        var body = "{\"connectionId\":\"" + c[0] + "\",\"externalId\":\"4201\"}";
        var path = "/api/v1/projects/" + project + "/cicd/repositories";
        f.send(junho, post(path), body).andExpect(status().isForbidden());
        f.send(minji, post(path), body).andExpect(status().isForbidden());
        f.send(junho, get(path), "").andExpect(status().isOk());
        f.send(minji, get(path), "").andExpect(status().isOk());
        f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections"), "").andExpect(status().isForbidden());
        f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "").andExpect(status().isForbidden());
        // a non-member does not learn the project exists
        f.send(doyoon, get(path), "").andExpect(status().isNotFound());
        f.send(doyoon, post(path), body).andExpect(status().isNotFound());
        assertThat(git.requests()).isEmpty();
    }

    @Test void aRepositoryBelongsToOneProject() throws Exception {
        var first = leaderProject("cicd");
        var second = leaderProject("cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        attach(junho, first, c[0], "4201");
        var res = f.send(junho, post("/api/v1/projects/" + second + "/cicd/repositories"),
                "{\"connectionId\":\"" + c[0] + "\",\"externalId\":\"4201\"}").andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("이미 다른 프로젝트에 붙은 저장소입니다.").doesNotContain(first).doesNotContain("개발 연동");
        f.send(junho, post("/api/v1/projects/" + first + "/cicd/repositories"),
                "{\"connectionId\":\"" + c[0] + "\",\"externalId\":\"4201\"}").andExpect(status().isConflict());
        var search = f.send(junho, get("/api/v1/projects/" + second + "/cicd/connections/" + c[0] + "/search?q=web"), "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(search).doesNotContain(first).doesNotContain("개발 연동");
    }

    @Test void projectWithoutTheModuleHidesEverything() throws Exception {
        var project = leaderProject("work_packages");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        var path = "/api/v1/projects/" + project + "/cicd/repositories";
        f.send(junho, get(path), "").andExpect(status().isNotFound());
        f.send(junho, post(path), "{\"connectionId\":\"" + c[0] + "\",\"externalId\":\"4201\"}").andExpect(status().isNotFound());
        f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections"), "").andExpect(status().isNotFound());
    }

    @Test void searchNeedsAToken() throws Exception {
        var project = leaderProject("cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), null);
        var res = f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "")
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("이 연동에는 API 토큰이 없어 저장소를 찾을 수 없습니다.");
        assertThat(git.requests()).isEmpty();
    }

    @Test void attachedPathComesFromTheRemoteNotTheClient() throws Exception {
        var project = leaderProject("cicd");
        var c = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
        f.send(junho, post("/api/v1/projects/" + project + "/cicd/repositories"),
                "{\"connectionId\":\"" + c[0] + "\",\"externalId\":\"4201\",\"fullPath\":\"evil/other\",\"webUrl\":\"https://evil.example\"}")
                .andExpect(status().isCreated());
        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(UUID.fromString(c[0]))).fetchOne();
        assertThat(row.getFullPath()).isEqualTo("openpms-test/web");
        assertThat(row.getWebUrl()).isEqualTo("https://gitlab.example.com/openpms-test/web");
        // an id the remote does not know, and a remote that fails
        f.send(junho, post("/api/v1/projects/" + project + "/cicd/repositories"), "{\"connectionId\":\"" + c[0] + "\",\"externalId\":\"999999\"}")
                .andExpect(status().isUnprocessableEntity());
        git.respond("/api/v4/projects", 500, "secret remote trouble");
        var res = f.send(junho, get("/api/v1/projects/" + project + "/cicd/connections/" + c[0] + "/search?q=web"), "")
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("연동한 도구에 연결하지 못했습니다.").doesNotContain("secret remote trouble");
    }
}

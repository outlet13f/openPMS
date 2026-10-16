package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** Environment definitions: the API, the defaults when the module goes on, and aliases taking unassigned deployments. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdEnvironmentsIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEnvironments environments;
    CicdFixture f;
    MockHttpSession admin, junho, minji;
    UUID connection;
    int seq;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        minji = f.login(CicdFixture.MINJI_EMAIL);
        connection = UUID.fromString(f.newConnection(admin, "gitlab", "https://gitlab.example.com", null)[0]);
    }

    private UUID projectId(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne(PROJECTS.ID);
    }

    private static String url(String p) {
        return "/api/v1/projects/" + p + "/cicd/environments";
    }

    private JsonNode view(MockHttpSession who, String p) throws Exception {
        return f.body(f.send(who, get(url(p)), "").andExpect(status().isOk()).andReturn());
    }

    private String envId(String p, String name) throws Exception {
        for (var e : view(admin, p).get("environments")) if (e.get("name").asString().equals(name)) return e.get("id").asString();
        throw new AssertionError(name);
    }

    private UUID repo(String project) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, id).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, "x-" + id).set(CICD_REPOSITORIES.FULL_PATH, "g/" + id)
                .set(CICD_REPOSITORIES.WEB_URL, "https://gitlab.example.com/g/" + id).set(CICD_REPOSITORIES.PROJECT_ID, projectId(project)).execute();
        return id;
    }

    private UUID deployment(UUID repo, UUID env, String name, String status, String sha, OffsetDateTime at) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.ID, id).set(CICD_DEPLOYMENTS.REPOSITORY_ID, repo).set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, env)
                .set(CICD_DEPLOYMENTS.ENVIRONMENT_NAME, name).set(CICD_DEPLOYMENTS.EXTERNAL_ID, "d" + (++seq)).set(CICD_DEPLOYMENTS.STATUS, status)
                .set(CICD_DEPLOYMENTS.SHA, sha).set(CICD_DEPLOYMENTS.DEPLOYED_AT, at).set(CICD_DEPLOYMENTS.SOURCE, "gitlab").execute();
        return id;
    }

    private String sha() {
        return String.format("%040x", ++seq);
    }

    private UUID environmentOf(UUID deployment) {
        return db.select(CICD_DEPLOYMENTS.ENVIRONMENT_ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(deployment)).fetchOne(0, UUID.class);
    }

    private String inclusion(UUID deployment) {
        return db.select(CICD_DEPLOYMENTS.INCLUSION).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(deployment)).fetchOne(0, String.class);
    }

    @Test void turningTheModuleOnAddsTheDefaults() throws Exception {
        var p = f.newProject(admin, "work_packages", "cicd");   // newProject patches the modules: that is "turning on"
        var view = view(junho, p);
        assertThat(view.get("environments")).extracting(n -> n.get("name").asString()).containsExactly("개발", "스테이징", "운영");
        assertThat(view.at("/environments/2/production").asBoolean()).isTrue();
        assertThat(view.get("canManage").asBoolean()).isFalse();   // 박준호 is 멤버 here
        // off and on again keeps what is there
        f.send(admin, patch("/api/v1/projects/" + p), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        f.send(admin, patch("/api/v1/projects/" + p), "{\"enabledModules\":[\"work_packages\",\"cicd\"]}").andExpect(status().isOk());
        assertThat(db.fetchCount(CICD_ENVIRONMENTS, CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId(p)))).isEqualTo(3);
    }

    @Test void aliasesMatchIgnoringCase() throws Exception {
        var p = f.newProject(admin, "work_packages", "cicd");
        assertThat(environments.match(projectId(p), "PRODUCTION")).isNotNull();
        assertThat(environments.match(projectId(p), "운영")).isNotNull();   // the name counts too
        assertThat(environments.match(projectId(p), "qa")).isNull();
    }

    @Test void devLeadManages() throws Exception {
        var p = f.newProject(admin, "cicd");
        f.addMember(admin, p, CicdFixture.DOYOON, CicdFixture.ROLE_DEV_LEAD);
        var lead = f.login("doyoon.han@openpms.local");
        assertThat(view(lead, p).get("canManage").asBoolean()).isTrue();
        var created = f.body(f.send(lead, post(url(p)), "{\"name\":\"QA\",\"aliases\":[\" QA \",\"qa\",\"Test\"]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.position").value(4)).andReturn());
        assertThat(created.get("aliases")).extracting(n -> n.asString()).containsExactly("qa", "test");
        var id = created.get("id").asString();
        f.send(lead, patch(url(p) + "/" + id), "{\"name\":\"품질 검증\",\"production\":false,\"aliases\":[\"QA\",\"Test\",\"UAT\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("품질 검증")).andExpect(jsonPath("$.aliases[2]").value("uat"));
        var ids = new ArrayList<String>();
        for (var e : view(lead, p).get("environments")) ids.add(e.get("id").asString());
        var reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        var body = "{\"ids\":[\"" + String.join("\",\"", reversed) + "\"]}";
        f.send(lead, put(url(p) + "/order"), body).andExpect(status().isOk()).andExpect(jsonPath("$.environments[0].id").value(id))
                .andExpect(jsonPath("$.environments[3].name").value("개발"));
        f.send(lead, put(url(p) + "/order"), "{\"ids\":[\"" + ids.get(0) + "\"]}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("ids")).andExpect(jsonPath("$.errors[0].message").value("환경 순서가 올바르지 않습니다."));
        f.send(lead, post(url(p)), "{\"name\":\"\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("환경 이름은 1자에서 50자 사이여야 합니다."));
        f.send(lead, post(url(p)), "{\"name\":\"x\",\"aliases\":[\"a,b\"]}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("별칭이 올바르지 않습니다."));
        f.send(lead, patch(url(p) + "/" + UUID.randomUUID()), "{}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("환경을 찾을 수 없습니다."));
    }

    @Test void membersAndObserversOnlyView() throws Exception {
        var p = f.newProject(admin, "cicd");
        var id = envId(p, "개발");
        for (var who : new MockHttpSession[] {junho, minji}) {
            f.send(who, get(url(p)), "").andExpect(status().isOk());
            f.send(who, post(url(p)), "{\"name\":\"x\"}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("환경을 관리할 권한이 없습니다."));
            f.send(who, patch(url(p) + "/" + id), "{\"name\":\"y\"}").andExpect(status().isForbidden());
            f.send(who, put(url(p) + "/order"), "{\"ids\":[]}").andExpect(status().isForbidden());
            f.send(who, delete(url(p) + "/" + id), "").andExpect(status().isForbidden());
        }
        var off = f.newProject(admin, "work_packages");
        f.send(junho, get(url(off)), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("프로젝트를 찾을 수 없습니다."));
        f.send(admin, post(url(off)), "{\"name\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test void aliasClashIsRefused() throws Exception {
        var p = f.newProject(admin, "cicd");
        var stg = envId(p, "스테이징");
        f.send(admin, patch(url(p) + "/" + stg), "{\"aliases\":[\"stg\",\"prod\"]}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("aliases"))
                .andExpect(jsonPath("$.errors[0].message").value("다른 환경이 이미 쓰는 이름입니다: prod"));
        f.send(admin, patch(url(p) + "/" + stg), "{\"aliases\":[\"운영\"]}").andExpect(status().isUnprocessableContent());
        f.send(admin, post(url(p)), "{\"name\":\"운영\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("같은 이름의 환경이 있습니다."));
        f.send(admin, post(url(p)), "{\"name\":\"STAGING\"}").andExpect(status().isUnprocessableContent());   // another environment's alias
        f.send(admin, patch(url(p) + "/" + stg), "{\"name\":\"개발\"}").andExpect(status().isConflict());
        f.send(admin, patch(url(p) + "/" + stg), "{\"name\":\"스테이징\"}").andExpect(status().isOk());   // itself is no clash
    }

    @Test void deleteHidesWhenDeploymentsExist() throws Exception {
        var p = f.newProject(admin, "cicd");
        var dev = envId(p, "개발");
        deployment(repo(p), UUID.fromString(dev), "dev", "success", sha(), OffsetDateTime.now());
        f.send(admin, delete(url(p) + "/" + dev), "").andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(false));
        var shown = view(admin, p).get("environments").get(0);
        assertThat(shown.get("hidden").asBoolean()).isTrue();
        assertThat(shown.get("deploymentCount").asInt()).isEqualTo(1);
        assertThat(environments.match(projectId(p), "dev")).isNull();
        var qa = f.body(f.send(admin, post(url(p)), "{\"name\":\"QA\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        f.send(admin, delete(url(p) + "/" + qa), "").andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        assertThat(db.fetchExists(CICD_ENVIRONMENTS, CICD_ENVIRONMENTS.ID.eq(UUID.fromString(qa)))).isFalse();
    }

    @Test void addingAnAliasReassignsUnassignedDeployments() throws Exception {
        var p = f.newProject(admin, "cicd");
        var repo = repo(p);
        var ok = deployment(repo, null, "QA-1", "success", sha(), OffsetDateTime.now().minusHours(3));
        var failed = deployment(repo, null, "qa-1", "failed", sha(), OffsetDateTime.now().minusHours(2));
        var noSha = deployment(repo, null, "Qa-1", "success", null, OffsetDateTime.now().minusHours(1));
        var other = deployment(repo, null, "elsewhere", "success", sha(), OffsetDateTime.now());
        var otherProject = f.newProject(admin, "cicd");
        var foreign = deployment(repo(otherProject), null, "qa-1", "success", sha(), OffsetDateTime.now());   // another project's repository
        assertThat(view(admin, p).get("unassigned")).extracting(n -> n.get("name").asString()).containsExactly("qa-1", "elsewhere");
        var stg = UUID.fromString(envId(p, "스테이징"));
        f.send(admin, patch(url(p) + "/" + stg), "{\"aliases\":[\"stg\",\"staging\",\"qa-1\"]}").andExpect(status().isOk());
        for (var d : new UUID[] {ok, failed, noSha}) assertThat(environmentOf(d)).isEqualTo(stg);
        assertThat(inclusion(ok)).isEqualTo("checking");
        assertThat(inclusion(failed)).isNull();
        assertThat(inclusion(noSha)).isEqualTo("unknown_commit");
        assertThat(environmentOf(other)).isNull();
        assertThat(environmentOf(foreign)).isNull();
        assertThat(view(admin, p).get("unassigned")).extracting(n -> n.get("name").asString()).containsExactly("elsewhere");
        // a detached repository of this project counts too
        var detached = repo(p);
        db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.DETACHED_AT, OffsetDateTime.now()).where(CICD_REPOSITORIES.ID.eq(detached)).execute();
        var late = deployment(detached, null, "ELSEWHERE", "success", sha(), OffsetDateTime.now());
        f.send(admin, patch(url(p) + "/" + stg), "{\"aliases\":[\"stg\",\"staging\",\"qa-1\",\"elsewhere\"]}").andExpect(status().isOk());
        assertThat(environmentOf(late)).isEqualTo(stg);
        assertThat(environmentOf(other)).isEqualTo(stg);
    }

    @Test void aMovedRepositoryStaysOutOfThisProjectsReassign() throws Exception {
        var p = f.newProject(admin, "cicd");
        var q = f.newProject(admin, "cicd");
        var moved = repo(p);
        var d = deployment(moved, null, "qa-9", "success", sha(), OffsetDateTime.now());
        // the row moves to q (detach, then attach from q): its history stays, its current project is q
        db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.PROJECT_ID, projectId(q)).where(CICD_REPOSITORIES.ID.eq(moved)).execute();
        f.send(admin, patch(url(p) + "/" + envId(p, "스테이징")), "{\"aliases\":[\"qa-9\"]}").andExpect(status().isOk());
        assertThat(environmentOf(d)).isNull();
        assertThat(view(admin, p).get("unassigned").size()).isZero();
        assertThat(view(admin, q).get("unassigned").size()).isEqualTo(1);
    }

    @Test void creatingRenamingAndUnhidingReassign() throws Exception {
        var p = f.newProject(admin, "cicd");
        var repo = repo(p);
        var byAlias = deployment(repo, null, "Qa-1", "success", sha(), OffsetDateTime.now());
        var byName = deployment(repo, null, "Verify", "success", sha(), OffsetDateTime.now());
        var hiddenOne = deployment(repo, null, "Later", "success", sha(), OffsetDateTime.now());
        var qa = UUID.fromString(f.body(f.send(admin, post(url(p)), "{\"name\":\"QA\",\"aliases\":[\"qa-1\"]}").andExpect(status().isCreated())
                .andReturn()).get("id").asString());
        assertThat(environmentOf(byAlias)).isEqualTo(qa);
        assertThat(inclusion(byAlias)).isEqualTo("checking");
        f.send(admin, patch(url(p) + "/" + qa), "{\"name\":\"verify\"}").andExpect(status().isOk());
        assertThat(environmentOf(byName)).isEqualTo(qa);
        // hidden, then new names do not match; visible again takes what waits for its name or aliases
        f.send(admin, patch(url(p) + "/" + qa), "{\"hidden\":true}").andExpect(status().isOk());
        f.send(admin, patch(url(p) + "/" + qa), "{\"aliases\":[\"qa-1\",\"later\"]}").andExpect(status().isOk());
        assertThat(environmentOf(hiddenOne)).isNull();
        f.send(admin, patch(url(p) + "/" + qa), "{\"hidden\":false}").andExpect(status().isOk());
        assertThat(environmentOf(hiddenOne)).isEqualTo(qa);
    }

    @Test void unassignedListGroupsByName() throws Exception {
        var p = f.newProject(admin, "cicd");
        var repo = repo(p);
        var t = OffsetDateTime.now().minusDays(1);
        deployment(repo, null, "Staging-2", "success", sha(), t.minusHours(2));
        deployment(repo, null, "Staging-2", "failed", sha(), t.minusHours(1));
        deployment(repo, null, "staging-2", "success", sha(), t);
        deployment(repo, null, "solo", "success", sha(), t);
        var u = view(admin, p).get("unassigned");
        assertThat(u.size()).isEqualTo(2);
        assertThat(u.get(0).get("name").asString()).isEqualTo("staging-2");
        assertThat(u.get(0).get("count").asInt()).isEqualTo(3);
        assertThat(OffsetDateTime.parse(u.get(0).get("lastAt").asString()).toInstant()).isCloseTo(t.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test void copyFromATemplateGetsTheDefaults() throws Exception {
        var template = f.newProject(admin, "cicd");
        f.send(admin, patch("/api/v1/projects/" + template), "{\"isTemplate\":true}").andExpect(status().isOk());
        f.send(admin, post(url(template)), "{\"name\":\"템플릿 전용\"}").andExpect(status().isCreated());
        try {
            var copy = "cd-" + UUID.randomUUID().toString().substring(0, 8);
            f.send(admin, post("/api/v1/projects"), "{\"name\":\"복사본\",\"identifier\":\"" + copy + "\",\"templateId\":\"" + template + "\"}")
                    .andExpect(status().isCreated());
            assertThat(view(admin, copy).get("environments")).extracting(n -> n.get("name").asString()).containsExactly("개발", "스테이징", "운영");
        } finally {
            db.update(PROJECTS).set(PROJECTS.IS_TEMPLATE, false).where(PROJECTS.IDENTIFIER.eq(template)).execute();   // other ITs count the templates
        }
    }

    @Test void apiTokensCannotReachEnvironments() throws Exception {
        db.deleteFrom(com.openpms.db.Tables.SETTINGS).where(com.openpms.db.Tables.SETTINGS.KEY.eq("api.tokens_enabled")).execute();
        var p = f.newProject(admin, "cicd");
        var secret = f.body(f.send(admin, post("/api/v1/me/api-tokens"), "{\"name\":\"환경 시험 토큰\",\"scope\":\"write\",\"expiresInDays\":30}")
                .andExpect(status().isCreated()).andReturn()).get("secret").asString();
        try {
            var refused = "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.";
            f.mvc.perform(get(url(p)).header("Authorization", "Bearer " + secret)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value(refused));
            f.mvc.perform(post(url(p)).header("Authorization", "Bearer " + secret).contentType("application/json").content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            f.mvc.perform(get(new java.net.URI(url(p).replace("environments", "%65nvironments"))).header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            // the repositories list stays open to a token
            f.mvc.perform(get("/api/v1/projects/" + p + "/cicd/repositories").header("Authorization", "Bearer " + secret)).andExpect(status().isOk());
        } finally {
            db.deleteFrom(com.openpms.db.Tables.API_TOKENS).where(com.openpms.db.Tables.API_TOKENS.NAME.eq("환경 시험 토큰")).execute();
        }
    }

    @Test void auditLines() throws Exception {
        var p = f.newProject(admin, "cicd");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("openpms.audit");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var id = f.body(f.send(admin, post(url(p)), "{\"name\":\"감사\",\"aliases\":[\"secretalias\"]}").andExpect(status().isCreated()).andReturn())
                    .get("id").asString();
            f.send(admin, patch(url(p) + "/" + id), "{\"aliases\":[\"othersecret\",\"third\"]}").andExpect(status().isOk());
            deployment(repo(p), UUID.fromString(id), "감사", "success", sha(), OffsetDateTime.now());
            f.send(admin, delete(url(p) + "/" + id), "").andExpect(status().isOk());
        } finally {
            logger.detachAppender(appender);
        }
        var lines = appender.list.stream().map(e -> e.getFormattedMessage()).filter(m -> m.startsWith("cicd.environment.")).toList();
        assertThat(lines).anyMatch(m -> m.startsWith("cicd.environment.created by=") && m.contains(" name=감사"));
        assertThat(lines).anyMatch(m -> m.startsWith("cicd.environment.aliases_changed by=") && m.endsWith("added=2 removed=1"));
        assertThat(lines).anyMatch(m -> m.startsWith("cicd.environment.hidden by="));
        assertThat(lines).noneMatch(m -> m.contains("secretalias") || m.contains("othersecret") || m.contains("third"));
    }
}

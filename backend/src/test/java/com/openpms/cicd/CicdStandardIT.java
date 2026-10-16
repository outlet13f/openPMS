package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_PIPELINES;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Standard notices (Jenkins, Argo CD) find their repository by path among the connection's allowed projects. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdStandardIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdProcessor processor;
    @Autowired CicdEvents events;
    MockMvc mvc;
    CicdFixture f;
    MockHttpSession admin;
    /** [id, secret, hookKey] of the Jenkins connection. */
    String[] jenkins;
    UUID gitlab;
    String projectA;

    @BeforeEach void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        f = new CicdFixture(mvc);
        admin = f.login(CicdFixture.ADMIN);
        jenkins = f.newConnection(admin, "jenkins", "https://jenkins.local", null);
        gitlab = UUID.fromString(f.newConnection(admin, "gitlab", "https://gitlab.example.com", null)[0]);
        projectA = f.newProject(admin, "work_packages", "cicd");
        f.allow(admin, jenkins[0], projectA);
    }

    private static String sign(byte[] body, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private UUID projectId(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne().value1();
    }

    private UUID repository(UUID connection, String project, String path, String webUrl) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, id).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, "x-" + id).set(CICD_REPOSITORIES.FULL_PATH, path).set(CICD_REPOSITORIES.WEB_URL, webUrl)
                .set(CICD_REPOSITORIES.PROJECT_ID, projectId(project)).execute();
        return id;
    }

    private UUID portal(String project) {
        return repository(gitlab, project, "web/portal", "https://gitlab.example.com/web/portal");
    }

    /** 40 random hex digits starting with a letter, so a short tag of it is never digits only (which is no commit number). */
    private static String sha() {
        return ("a" + UUID.randomUUID() + UUID.randomUUID()).replace("-", "").substring(0, 40);
    }

    private static String at() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String deploymentNotice(String id, String image, String environment, String url, String at) {
        return "{\"type\":\"deployment\",\"repository\":\"WEB/portal\",\"id\":\"" + id + "\",\"image\":\"" + image + "\",\"environment\":\""
                + environment + "\",\"status\":\"success\",\"url\":\"" + url + "\",\"at\":\"" + at + "\"}";
    }

    /** Posts the notice signed with the Jenkins connection's secret; returns "accepted". */
    private int send(String notice) throws Exception {
        var body = notice.getBytes(StandardCharsets.UTF_8);
        var r = mvc.perform(post("/api/v1/hooks/cicd/" + jenkins[2]).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-OpenPMS-Signature", sign(body, jenkins[1]))).andExpect(status().isAccepted()).andReturn();
        return f.body(r).get("accepted").asInt();
    }

    private void deliver(String notice) throws Exception {
        assertThat(send(notice)).isEqualTo(1);
        processor.run(Instant.now());
    }

    private int eventsLeft() {
        return db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(jenkins[0])));
    }

    private int deploymentsOf(UUID repo) {
        return db.fetchCount(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo));
    }

    /** id: the notice's own id; Jenkins ids are stored with the tool's name in front. */
    private com.openpms.db.tables.records.CicdDeploymentsRecord deploymentRow(UUID repo, String id) {
        return db.selectFrom(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo), CICD_DEPLOYMENTS.EXTERNAL_ID.eq("jenkins:" + id)).fetchOne();
    }

    @Test void jenkinsDeploymentReachesAnAllowedRepository() throws Exception {
        var repo = portal(projectA);
        var full = sha();
        db.insertInto(CICD_COMMITS).set(CICD_COMMITS.REPOSITORY_ID, repo).set(CICD_COMMITS.SHA, full).set(CICD_COMMITS.TITLE, "c")
                .set(CICD_COMMITS.COMMITTED_AT, OffsetDateTime.now()).set(CICD_COMMITS.WEB_URL, "").execute();
        var url = "https://jenkins.local/job/portal/42/";
        deliver(deploymentNotice("portal-42", "registry.local/web/portal:" + full.substring(0, 8), "production", url, at()));

        var row = deploymentRow(repo, "portal-42");
        assertThat(row).isNotNull();
        var production = db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS)
                .where(CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId(projectA)), CICD_ENVIRONMENTS.NAME.eq("운영")).fetchOne(CICD_ENVIRONMENTS.ID);
        assertThat(row.getEnvironmentId()).isEqualTo(production);
        assertThat(row.getSha()).isEqualTo(full);
        assertThat(row.getSource()).isEqualTo("jenkins");
        assertThat(row.getWebUrl()).isEqualTo(url);
        assertThat(row.getImage()).isEqualTo("registry.local/web/portal:" + full.substring(0, 8));
        assertThat(row.getInclusion()).isIn("checking", "first");

        // a link off the Jenkins address is not kept
        deliver(deploymentNotice("portal-43", "registry.local/web/portal:" + full.substring(0, 8), "production", "https://evil.example.com/x", at()));
        assertThat(deploymentRow(repo, "portal-43").getWebUrl()).isEmpty();
    }

    @Test void aJenkinsIdNeverSharesARowWithAGitLabId() throws Exception {
        var repo = portal(projectA);
        var gitlabSha = sha();
        events.store(gitlab, java.util.List.of(new CicdEvent("deployment", "deployment:" + repo + ":15:success", "x-" + repo, null, null, null,
                new CicdEvent.Deployment("15", gitlabSha, null, "production", "success", "", OffsetDateTime.now().minusHours(2)), null)), "webhook");
        processor.run(Instant.now());
        deliver(deploymentNotice("15", "web/portal:latest", "production", "", at()));
        var rows = db.selectFrom(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo)).fetchMap(CICD_DEPLOYMENTS.EXTERNAL_ID);
        assertThat(rows.keySet()).containsExactlyInAnyOrder("15", "jenkins:15");
        assertThat(rows.get("15").getSource()).isEqualTo("gitlab");
        assertThat(rows.get("15").getSha()).isEqualTo(gitlabSha);
        assertThat(rows.get("jenkins:15").getSource()).isEqualTo("jenkins");
        assertThat(rows.get("jenkins:15").getSha()).isNull();
    }

    @Test void repositoriesOutsideTheAllowedProjectsAreIgnored() throws Exception {
        var b = f.newProject(admin, "work_packages", "cicd");
        var other = portal(b);
        var notice = deploymentNotice("portal-1", "web/portal:" + sha().substring(0, 8), "production", "", at());
        deliver(notice);
        assertThat(deploymentsOf(other)).isZero();
        assertThat(eventsLeft()).isZero();
    }

    @Test void detachedRepositoryIsNotMatched() throws Exception {
        var repo = portal(projectA);
        db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.DETACHED_AT, OffsetDateTime.now()).where(CICD_REPOSITORIES.ID.eq(repo)).execute();
        deliver(deploymentNotice("portal-2", "web/portal:" + sha().substring(0, 8), "production", "", at()));
        assertThat(deploymentsOf(repo)).isZero();
        assertThat(eventsLeft()).isZero();
    }

    @Test void ambiguousPathIsDropped() throws Exception {
        var github = UUID.fromString(f.newConnection(admin, "github", "https://api.github.com", null)[0]);
        var a = portal(projectA);
        var b = repository(github, projectA, "Web/Portal", "https://github.com/Web/Portal");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CicdProcessor.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            deliver(deploymentNotice("portal-3", "web/portal:" + sha().substring(0, 8), "production", "", at()));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(deploymentsOf(a) + deploymentsOf(b)).isZero();
        assertThat(eventsLeft()).isZero();
        var warnings = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).filter(m -> m.contains(jenkins[0])).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("matches 2 repositories").doesNotContain("portal").doesNotContain("Portal");
    }

    @Test void unknownImageTagIsUnknownCommit() throws Exception {
        var repo = portal(projectA);
        deliver(deploymentNotice("portal-4", "web/portal:latest", "production", "", at()));
        var row = deploymentRow(repo, "portal-4");
        assertThat(row).isNotNull();
        assertThat(row.getSha()).isNull();
        assertThat(row.getInclusion()).isEqualTo("unknown_commit");
    }

    @Test void ownTagRuleIsUsed() throws Exception {
        var repo = portal(projectA);
        db.update(CICD_CONNECTIONS).set(CICD_CONNECTIONS.IMAGE_TAG_PATTERN, "-g([0-9a-f]{7,40})$")
                .where(CICD_CONNECTIONS.ID.eq(UUID.fromString(jenkins[0]))).execute();
        deliver(deploymentNotice("portal-5", "web/portal:2026.10-g9f8e7d6", "production", "", at()));
        assertThat(deploymentRow(repo, "portal-5").getSha()).isEqualTo("9f8e7d6");
        // the default rule would take the first hex run here; the connection's rule takes the one after -g
        deliver(deploymentNotice("portal-6", "web/portal:deadbeef1-g9f8e7d6", "production", "", at()));
        assertThat(deploymentRow(repo, "portal-6").getSha()).isEqualTo("9f8e7d6");
    }

    @Test void jenkinsPipelineIsRecorded() throws Exception {
        var repo = portal(projectA);
        var sha = sha();
        deliver("{\"type\":\"pipeline\",\"repository\":\"web/PORTAL\",\"id\":\"portal-build-7\",\"name\":\"portal-build\",\"sha\":\"" + sha
                + "\",\"ref\":\"main\",\"status\":\"failed\",\"url\":\"https://evil.example.com/job/7\",\"at\":\"" + at() + "\"}");
        var row = db.selectFrom(CICD_PIPELINES).where(CICD_PIPELINES.REPOSITORY_ID.eq(repo), CICD_PIPELINES.EXTERNAL_ID.eq("jenkins:portal-build-7")).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getSource()).isEqualTo("jenkins");
        assertThat(row.getName()).isEqualTo("portal-build");
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getSha()).isEqualTo(sha);
        assertThat(row.getRef()).isEqualTo("main");
        assertThat(row.getWebUrl()).isEmpty();
        assertThat(row.getFinishedAt()).isNotNull();
    }

    @Test void noticeTimesOutsideTheAllowedRangeAreRefused() throws Exception {
        var repo = portal(projectA);
        for (var at : new String[] {"1999-12-31T23:59:59Z", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.SECONDS).toString()}) {
            deliver(deploymentNotice("portal-t-" + at.substring(0, 4), "web/portal:latest", "production", "", at));
        }
        assertThat(deploymentsOf(repo)).isZero();
        var failed = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(jenkins[0]))).fetch();
        assertThat(failed).hasSize(2).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo("failed");
            assertThat(e.getError()).contains("time out of range");
        });
    }

    @Test void nulAndLoneSurrogatesAreNotStored() throws Exception {
        portal(projectA);
        // JSON escapes: a NUL in the id, a lone high surrogate in the environment
        assertThat(send(deploymentNotice("portal\\u0000x", "web/portal:latest", "production", "", at()))).isZero();
        assertThat(send(deploymentNotice("portal-8", "web/portal:latest", "prod\\ud800", "", at()))).isZero();
        assertThat(eventsLeft()).isZero();
    }
}

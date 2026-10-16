package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_PIPELINES;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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

/** Pipeline and deployment events of a GitLab connection become cicd_pipelines and cicd_deployments rows. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdBuildsIT {
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    CicdFixture f;
    MockHttpSession admin;
    UUID connection;
    int seq;

    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        connection = UUID.fromString(f.newConnection(admin, "gitlab", "https://gitlab.example.com", null)[0]);
    }

    private UUID projectId(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne().value1();
    }

    /** A project with the cicd module, so it has 개발·스테이징·운영; a repository of the GitLab connection attached to it. */
    private UUID attachedRepository() throws Exception {
        var p = f.newProject(admin, "work_packages", "cicd");
        var id = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, id).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, "x-" + id).set(CICD_REPOSITORIES.FULL_PATH, "g/" + id)
                .set(CICD_REPOSITORIES.WEB_URL, "https://gitlab.example.com/g/" + id).set(CICD_REPOSITORIES.PROJECT_ID, projectId(p))
                .execute();
        return id;
    }

    private String sha() {
        return String.format("%040x", UUID.randomUUID().getMostSignificantBits() ^ (++seq)).replace(' ', '0');
    }

    private String ext(UUID repo) {
        return "x-" + repo;
    }

    private CicdEvent pipeline(UUID repo, String id, String sha, String status, OffsetDateTime at, String url) {
        var running = status.equals("running");
        return new CicdEvent("pipeline", "pipeline:" + repo + ":" + id + ":" + status + ":" + at.toEpochSecond(), ext(repo), null, null,
                new CicdEvent.Pipeline(id, "build", sha, "main", status, url, running ? at : null, running ? null : at, at), null, null);
    }

    private CicdEvent deployment(UUID repo, String id, String sha, String environment, String status, OffsetDateTime at) {
        return new CicdEvent("deployment", "deployment:" + repo + ":" + id + ":" + status + ":" + at.toEpochSecond(), ext(repo), null, null,
                null, new CicdEvent.Deployment(id, sha, null, environment, status, "https://gitlab.example.com/d/" + id, at), null);
    }

    private void feed(CicdEvent... list) {
        events.store(connection, List.of(list), "webhook");
        processor.run(Instant.now());
    }

    private com.openpms.db.tables.records.CicdDeploymentsRecord deploymentRow(UUID repo, String id) {
        return db.selectFrom(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo), CICD_DEPLOYMENTS.EXTERNAL_ID.eq(id)).fetchOne();
    }

    private UUID environment(UUID repo, String name) {
        return db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS).join(CICD_REPOSITORIES).on(CICD_REPOSITORIES.PROJECT_ID.eq(CICD_ENVIRONMENTS.PROJECT_ID))
                .where(CICD_REPOSITORIES.ID.eq(repo), CICD_ENVIRONMENTS.NAME.eq(name)).fetchOne(CICD_ENVIRONMENTS.ID);
    }

    private void commitRow(UUID repo, String sha) {
        db.insertInto(CICD_COMMITS).set(CICD_COMMITS.REPOSITORY_ID, repo).set(CICD_COMMITS.SHA, sha).set(CICD_COMMITS.TITLE, "c")
                .set(CICD_COMMITS.COMMITTED_AT, T0).set(CICD_COMMITS.WEB_URL, "").execute();
    }

    @Test void samePipelineManyTimesKeepsTheLatest() throws Exception {
        var repo = attachedRepository();
        var sha = sha();
        var url = "https://gitlab.example.com/g/" + repo + "/-/pipelines/7";
        feed(pipeline(repo, "7", sha, "running", T0, url));
        feed(pipeline(repo, "7", sha, "success", T0.plusMinutes(2), url));
        feed(pipeline(repo, "7", sha, "running", T0.plusMinutes(1), url));   // late: older than what is stored
        // the same state again under another key (webhook and poll): no error, no change
        events.store(connection, List.of(new CicdEvent("pipeline", "pipeline:poll:" + repo + ":7", ext(repo), null, null,
                new CicdEvent.Pipeline("7", "build", sha, "main", "success", url, null, T0.plusMinutes(2), T0.plusMinutes(2)), null, null)), "poll");
        processor.run(Instant.now());

        var rows = db.selectFrom(CICD_PIPELINES).where(CICD_PIPELINES.REPOSITORY_ID.eq(repo)).fetch();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo("success");
        assertThat(row.getFinishedAt().toInstant()).isEqualTo(T0.plusMinutes(2).toInstant());
        assertThat(row.getStartedAt().toInstant()).isEqualTo(T0.toInstant());
        assertThat(row.getSource()).isEqualTo("gitlab");
        assertThat(row.getName()).isEqualTo("build");
        assertThat(row.getWebUrl()).isEqualTo(url);
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(connection), CICD_EVENTS.STATUS.ne("done"))).isZero();
    }

    @Test void pipelineLinkMustBeOnTheRepositoryHost() throws Exception {
        var repo = attachedRepository();
        feed(pipeline(repo, "8", sha(), "failed", T0, "https://evil.example.com/g/x/-/pipelines/8"));
        var row = db.selectFrom(CICD_PIPELINES).where(CICD_PIPELINES.REPOSITORY_ID.eq(repo), CICD_PIPELINES.EXTERNAL_ID.eq("8")).fetchOne();
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getWebUrl()).isEmpty();
    }

    @Test void deploymentMatchesTheEnvironment() throws Exception {
        var repo = attachedRepository();
        feed(deployment(repo, "1", sha(), "Staging", "success", T0), deployment(repo, "2", sha(), "qa", "success", T0));
        var staging = deploymentRow(repo, "1");
        assertThat(staging.getEnvironmentId()).isEqualTo(environment(repo, "스테이징"));
        assertThat(staging.getEnvironmentName()).isEqualTo("Staging");
        assertThat(staging.getStatus()).isEqualTo("success");
        assertThat(staging.getInclusion()).isIn("checking", "first");
        assertThat(staging.getSource()).isEqualTo("gitlab");
        assertThat(staging.getWebUrl()).isEqualTo("https://gitlab.example.com/d/1");
        var qa = deploymentRow(repo, "2");
        assertThat(qa.getEnvironmentId()).isNull();
        assertThat(qa.getInclusion()).isNull();
    }

    @Test void onlySuccessWaitsForInclusion() throws Exception {
        var repo = attachedRepository();
        var sha = sha();
        feed(deployment(repo, "5", sha, "production", "running", T0));
        assertThat(deploymentRow(repo, "5").getInclusion()).isNull();
        assertThat(deploymentRow(repo, "5").getEnvironmentId()).isEqualTo(environment(repo, "운영"));
        feed(deployment(repo, "5", sha, "production", "success", T0.plusMinutes(1)));
        assertThat(deploymentRow(repo, "5").getStatus()).isEqualTo("success");
        assertThat(deploymentRow(repo, "5").getInclusion()).isIn("checking", "first");
        // a success is never reopened, even by a later notice
        feed(deployment(repo, "5", sha, "production", "failed", T0.plusMinutes(5)));
        var row = deploymentRow(repo, "5");
        assertThat(row.getStatus()).isEqualTo("success");
        assertThat(row.getDeployedAt().toInstant()).isEqualTo(T0.plusMinutes(1).toInstant());
        assertThat(db.fetchCount(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
    }

    @Test void olderDeploymentNoticeDoesNotRollBack() throws Exception {
        var repo = attachedRepository();
        var sha = sha();
        feed(deployment(repo, "6", sha, "dev", "failed", T0.plusMinutes(3)));
        feed(deployment(repo, "6", sha, "dev", "running", T0));
        assertThat(deploymentRow(repo, "6").getStatus()).isEqualTo("failed");
        assertThat(deploymentRow(repo, "6").getInclusion()).isNull();
    }

    @Test void shortShaResolution() throws Exception {
        var repo = attachedRepository();
        var full = "1a2b3c4" + sha().substring(7);
        commitRow(repo, full);
        feed(deployment(repo, "10", "1a2b3c4", "dev", "success", T0));
        var one = deploymentRow(repo, "10");
        assertThat(one.getSha()).isEqualTo(full);
        assertThat(one.getInclusion()).isIn("checking", "first");

        // a short number nothing stored starts with stays as it is (the compare API resolves it)
        feed(deployment(repo, "11", "ffff000", "dev", "success", T0));
        assertThat(deploymentRow(repo, "11").getSha()).isEqualTo("ffff000");

        // two commits start the same: the commit is unknown
        commitRow(repo, "1a2b3c4" + sha().substring(7));
        feed(deployment(repo, "12", "1a2b3c4", "dev", "success", T0));
        var two = deploymentRow(repo, "12");
        assertThat(two.getSha()).isNull();
        assertThat(two.getInclusion()).isEqualTo("unknown_commit");
        assertThat(two.getEnvironmentId()).isEqualTo(environment(repo, "개발"));
    }

    @Test void mergeCommitIsStored() throws Exception {
        var repo = attachedRepository();
        var merge = sha();
        var t = T0;
        feed(new CicdEvent("merge_request", "merge_request:" + repo + ":3:" + t, ext(repo), null,
                new CicdEvent.MergeRequest(3, "병합", "", "merged", "topic", "main", "박준호", CicdFixture.JUNHO_EMAIL, t, t,
                        "https://gitlab.example.com/mr/3", merge.toUpperCase())),
                new CicdEvent("merge_request", "merge_request:" + repo + ":4:" + t, ext(repo), null,
                        new CicdEvent.MergeRequest(4, "이상한 번호", "", "merged", "topic2", "main", "박준호", CicdFixture.JUNHO_EMAIL, t, t,
                                "https://gitlab.example.com/mr/4", "not-a-sha")));
        var rows = db.select(CICD_MERGE_REQUESTS.IID, CICD_MERGE_REQUESTS.MERGE_COMMIT_SHA).from(CICD_MERGE_REQUESTS)
                .where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)).fetchMap(CICD_MERGE_REQUESTS.IID, CICD_MERGE_REQUESTS.MERGE_COMMIT_SHA);
        assertThat(rows.get(3L)).isEqualTo(merge);
        assertThat(rows).containsKey(4L);
        assertThat(rows.get(4L)).isNull();
    }

    @Test void incompleteDeploymentEventFails() throws Exception {
        var repo = attachedRepository();
        var bad = new CicdEvent("deployment", "deployment:" + repo + ":bad", ext(repo), null, null, null,
                new CicdEvent.Deployment("9", sha(), null, null, "success", "", T0), null);
        feed(bad);
        var row = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(connection), CICD_EVENTS.DELIVERY_KEY.eq(bad.deliveryKey())).fetchOne();
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getError()).contains("incomplete deployment event");
        assertThat(deploymentRow(repo, "9")).isNull();
    }
}

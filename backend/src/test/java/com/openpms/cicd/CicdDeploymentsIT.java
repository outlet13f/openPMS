package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
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
class CicdDeploymentsIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String A = "a0000000000000000000000000000000000000a1", B = "b0000000000000000000000000000000000000b2",
            C = "c0000000000000000000000000000000000000c3", H = "d0000000000000000000000000000000000000d4";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    CicdFixture f;
    MockHttpSession admin;
    FakeGitServer git;
    CommitGraph graph;
    UUID connection, repo;
    String project, ext;
    long wp;
    Instant clock = Instant.parse("2026-10-15T02:00:00Z");

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        ext = String.valueOf(70000 + (int) (Math.random() * 9999));
        graph = new CommitGraph().commit(A, "시작").commit(B, "로그인 고침", A).commit(C, "결제 붙임", B).commit(H, "핫픽스", A);
        git.respondWith("/api/v4/projects/" + ext + "/repository/compare", r -> graph.gitlabCompare(r.query()));
        connection = UUID.fromString(f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-deploy")[0]);
        project = f.newProject(admin, "work_packages", "cicd");
        f.allow(admin, connection.toString(), project);
        repo = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, repo).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, ext).set(CICD_REPOSITORIES.FULL_PATH, "g/svc-" + ext)
                .set(CICD_REPOSITORIES.WEB_URL, git.baseUrl() + "/g/svc-" + ext)
                .set(CICD_REPOSITORIES.PROJECT_ID, UUID.fromString(f.projectId(admin, project))).execute();
        wp = f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"배포 시험\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        // B carries the work package number; A, C and H do not
        push(A, "시작", T0);
        push(B, "로그인 고침 #" + wp, T0.plusMinutes(1));
        push(C, "결제 붙임", T0.plusMinutes(2));
        push(H, "핫픽스", T0.plusMinutes(3));
        run();
    }

    @AfterEach void tearDown() { git.close(); }

    void run() { processor.run(clock); clock = clock.plusSeconds(15); }

    void push(String sha, String message, OffsetDateTime at) {
        events.store(connection, List.of(new CicdEvent("commit", "commit:" + ext + ":" + sha, ext,
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, at, git.baseUrl() + "/c/" + sha), null)), "webhook");
    }

    UUID deploy(String id, String env, String sha, String status, OffsetDateTime at) {
        events.store(connection, List.of(new CicdEvent("deployment", "deployment:" + ext + ":" + id + ":" + status, ext, null, null, null,
                new CicdEvent.Deployment(id, sha, null, env, status, git.baseUrl() + "/g/svc-" + ext + "/-/jobs/" + id, at), null)), "webhook");
        run();
        return db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo), CICD_DEPLOYMENTS.EXTERNAL_ID.eq(id)).fetchOne().value1();
    }

    Set<String> in(String envName) {
        return Set.copyOf(db.select(CICD_COMMITS.SHA).from(CICD_ENVIRONMENT_COMMITS)
                .join(CICD_COMMITS).on(CICD_COMMITS.ID.eq(CICD_ENVIRONMENT_COMMITS.COMMIT_ID))
                .join(CICD_ENVIRONMENTS).on(CICD_ENVIRONMENTS.ID.eq(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID))
                .where(CICD_ENVIRONMENT_COMMITS.REPOSITORY_ID.eq(repo), CICD_ENVIRONMENTS.NAME.eq(envName)).fetch(CICD_COMMITS.SHA));
    }

    String inclusion(UUID d) {
        return db.select(CICD_DEPLOYMENTS.INCLUSION).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1();
    }

    UUID head(String envName) {
        return db.select(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID).from(CICD_ENVIRONMENT_HEADS)
                .join(CICD_ENVIRONMENTS).on(CICD_ENVIRONMENTS.ID.eq(CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID))
                .where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(repo), CICD_ENVIRONMENTS.NAME.eq(envName)).fetchOne().value1();
    }

    @Test void nextDeploymentAddsWhatItBrings() {
        var first = deploy("1", "dev", B, "success", T0.plusHours(1));
        assertThat(inclusion(first)).isEqualTo("first");   // the only linked commit is the deployed one
        assertThat(in("개발")).containsExactly(B);
        var next = deploy("2", "dev", C, "success", T0.plusHours(2));
        assertThat(inclusion(next)).isEqualTo("compared");
        assertThat(in("개발")).containsExactlyInAnyOrder(B, C);
        assertThat(head("개발")).isEqualTo(next);
    }

    @Test void rollbackTakesCommitsOut() {
        deploy("1", "prod", B, "success", T0.plusHours(1));
        deploy("2", "prod", C, "success", T0.plusHours(2));
        var back = deploy("3", "prod", B, "success", T0.plusHours(3));
        assertThat(inclusion(back)).isEqualTo("compared");
        assertThat(in("운영")).containsExactly(B);
    }

    @Test void divergedDeploymentAddsAndRemovesBothWays() {
        deploy("1", "stg", B, "success", T0.plusHours(1));
        deploy("2", "stg", H, "success", T0.plusHours(2));   // a hotfix branch from A: B leaves, H comes in
        assertThat(in("스테이징")).containsExactly(H);
    }

    @Test void onlySuccessfulDeploymentsChangeTheEnvironment() {
        deploy("1", "dev", B, "success", T0.plusHours(1));
        var running = deploy("2", "dev", C, "running", T0.plusHours(2));
        var failed = deploy("3", "dev", C, "failed", T0.plusHours(3));
        assertThat(inclusion(running)).isNull();
        assertThat(inclusion(failed)).isNull();
        assertThat(in("개발")).containsExactly(B);
    }

    @Test void lateOlderDeploymentIsOnlyRecorded() {
        var newer = deploy("2", "dev", C, "success", T0.plusHours(2));
        var older = deploy("1", "dev", B, "success", T0.plusHours(1));   // arrives after the newer one
        assertThat(inclusion(older)).isNull();
        assertThat(head("개발")).isEqualTo(newer);
    }

    @Test void redeployOfTheSameCommitOnlyMovesTheHead() {
        deploy("1", "dev", B, "success", T0.plusHours(1));
        int calls = git.requests().size();
        var again = deploy("2", "dev", B, "success", T0.plusHours(2));   // a restart: same image, same commit
        assertThat(inclusion(again)).isEqualTo("compared");
        assertThat(git.requests()).hasSize(calls);   // no compare call
        assertThat(head("개발")).isEqualTo(again);
    }

    @Test void moreThanAThousandOneWayIsPartial() {
        var g = new CommitGraph().commit(A, "시작").commit(B, "로그인 고침", A);
        String prev = B;
        for (int i = 1; i <= 1001; i++) { g.commit(g.sha(i), "커밋 " + i, prev); prev = g.sha(i); }
        graph = g;
        deploy("1", "dev", B, "success", T0.plusHours(1));   // first: B itself (the only linked commit), nothing to check
        var big = deploy("2", "dev", prev, "success", T0.plusHours(2));
        assertThat(inclusion(big)).isEqualTo("partial");
        assertThat(in("개발")).hasSize(1 + 1000);   // B, and the newest 1,000 of the 1,001 new commits
    }

    @Test void compareFailuresAreRetriedThenGiveUp() {
        deploy("1", "dev", B, "success", T0.plusHours(1));
        git.respondWith("/api/v4/projects/" + ext + "/repository/compare", r -> new FakeGitServer.Reply(500, "{}"));
        var d = deploy("2", "dev", C, "success", T0.plusHours(2));
        for (int i = 0; i < 6; i++) { clock = clock.plus(java.time.Duration.ofMinutes(9)); run(); }
        assertThat(inclusion(d)).isEqualTo("failed");
        assertThat(db.select(CICD_DEPLOYMENTS.ATTEMPTS, CICD_DEPLOYMENTS.CHECK_ERROR).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne())
                .satisfies(r -> { assertThat(r.value1()).isEqualTo(5); assertThat(r.value2()).isEqualTo("status 500"); });
        assertThat(head("개발")).isEqualTo(d);   // the next deployment compares against this one
        assertThat(in("개발")).containsExactly(B);
    }

    @Test void retriesWaitBetweenRuns() {
        deploy("1", "dev", B, "success", T0.plusHours(1));
        git.respondWith("/api/v4/projects/" + ext + "/repository/compare", r -> new FakeGitServer.Reply(503, "{}"));
        var d = deploy("2", "dev", C, "success", T0.plusHours(2));
        run();
        run();   // 30 seconds later: still waiting for the 1-minute retry
        assertThat(db.select(CICD_DEPLOYMENTS.ATTEMPTS).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1()).isEqualTo(1);
        assertThat(inclusion(d)).isEqualTo("checking");
    }

    @Test void twoDeploymentsAtOnceApplyInOrder() throws Exception {
        deploy("1", "prod", B, "success", T0.plusHours(1));
        for (var x : List.of(new Object[] {"2", C, 2}, new Object[] {"3", B, 3}))
            events.store(connection, List.of(new CicdEvent("deployment", "deployment:" + ext + ":" + x[0] + ":success", ext, null, null, null,
                    new CicdEvent.Deployment((String) x[0], (String) x[1], null, "prod", "success", "", T0.plusHours((int) x[2])), null)), "webhook");
        var error = new AtomicReference<Throwable>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) pool.submit(() -> { try { processor.run(clock); } catch (Throwable t) { error.set(t); } });
        }
        run();
        assertThat(error.get()).isNull();
        assertThat(in("운영")).containsExactly(B);   // C came in with 2 and left with 3, in that order
        var last = db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo), CICD_DEPLOYMENTS.EXTERNAL_ID.eq("3")).fetchOne().value1();
        assertThat(head("운영")).isEqualTo(last);
    }

    @Test void noTokenFailsAtOnce() throws Exception {
        deploy("1", "dev", B, "success", T0.plusHours(1));
        f.send(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/cicd/connections/" + connection),
                "{\"apiToken\":\"\"}").andExpect(status().isOk());
        var d = deploy("2", "dev", C, "success", T0.plusHours(2));
        assertThat(inclusion(d)).isEqualTo("failed");
        assertThat(db.select(CICD_DEPLOYMENTS.CHECK_ERROR).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1()).isEqualTo("no_token");
        assertThat(head("개발")).isEqualTo(d);
    }

    @Test void deploymentOfAMovedRepositoryIsOnlyRecorded() throws Exception {
        // a checking deployment to this project's environment, then the repository moves to another project before it is applied
        var dev = db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS)
                .where(CICD_ENVIRONMENTS.PROJECT_ID.eq(UUID.fromString(f.projectId(admin, project))), CICD_ENVIRONMENTS.NAME.eq("개발")).fetchOne().value1();
        var d = UUID.randomUUID();
        db.insertInto(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.ID, d).set(CICD_DEPLOYMENTS.REPOSITORY_ID, repo).set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, dev)
                .set(CICD_DEPLOYMENTS.ENVIRONMENT_NAME, "dev").set(CICD_DEPLOYMENTS.EXTERNAL_ID, "9").set(CICD_DEPLOYMENTS.SHA, B)
                .set(CICD_DEPLOYMENTS.STATUS, "success").set(CICD_DEPLOYMENTS.DEPLOYED_AT, T0.plusHours(1)).set(CICD_DEPLOYMENTS.SOURCE, "gitlab")
                .set(CICD_DEPLOYMENTS.INCLUSION, "checking").execute();
        var other = f.newProject(admin, "work_packages", "cicd");
        db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.PROJECT_ID, UUID.fromString(f.projectId(admin, other))).where(CICD_REPOSITORIES.ID.eq(repo)).execute();
        run();
        assertThat(inclusion(d)).isNull();
        assertThat(db.fetchCount(CICD_ENVIRONMENT_HEADS, CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(repo))).isZero();
    }

    @Test void errorCodesAndRetryDelays() {
        assertThat(CicdDeployments.errorCode(new UnexpectedStatus(401))).isEqualTo("status 401");
        assertThat(CicdDeployments.errorCode(new java.io.IOException("unexpected status 502"))).isEqualTo("status 502");
        assertThat(CicdDeployments.errorCode(new java.io.IOException("request timed out"))).isEqualTo("timeout");
        assertThat(CicdDeployments.errorCode(new java.io.IOException("response too large"))).isEqualTo("too_large");
        assertThat(CicdDeployments.errorCode(new java.io.IOException("request failed: refused"))).isEqualTo("failed");
        assertThat(CicdDeployments.errorCode(new IllegalStateException("secret could not be opened"))).isEqualTo("failed");
        assertThat(List.of(1, 2, 3, 4).stream().map(n -> CicdDeployments.retryDelay(n).toMinutes()).toList()).containsExactly(1L, 2L, 4L, 8L);
    }
}

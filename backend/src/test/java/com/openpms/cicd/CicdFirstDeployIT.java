package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_FIRST_DEPLOY_CHECKS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

/** A first deployment: which of the linked commits it already contains, asked of the code host (merge_base) a bounded number of times per run. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdFirstDeployIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String A = "a0000000000000000000000000000000000000a1", B = "b0000000000000000000000000000000000000b2",
            C = "c0000000000000000000000000000000000000c3", H = "d0000000000000000000000000000000000000d4",
            M = "e0000000000000000000000000000000000000e5";

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
    Instant clock = Instant.parse("2026-10-15T02:00:00Z");

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        ext = String.valueOf(80000 + (int) (Math.random() * 9999));
        graph = new CommitGraph();
        git.respondWith("/api/v4/projects/" + ext + "/repository/compare", r -> graph.gitlabCompare(r.query()));
        git.respondWith("/api/v4/projects/" + ext + "/repository/merge_base", r -> graph.gitlabMergeBase(r.query()));
        connection = UUID.fromString(f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-first")[0]);
        project = f.newProject(admin, "work_packages", "cicd");
        f.allow(admin, connection.toString(), project);
        repo = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, repo).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, ext).set(CICD_REPOSITORIES.FULL_PATH, "g/first-" + ext)
                .set(CICD_REPOSITORIES.WEB_URL, git.baseUrl() + "/g/first-" + ext)
                .set(CICD_REPOSITORIES.PROJECT_ID, UUID.fromString(f.projectId(admin, project))).execute();
    }

    @AfterEach void tearDown() { git.close(); }

    void run() { processor.run(clock); clock = clock.plusSeconds(15); }

    long newWorkPackage(String subject) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"" + subject + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    CicdEvent commit(String sha, String message, OffsetDateTime at) {
        return new CicdEvent("commit", "commit:" + ext + ":" + sha, ext,
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, at, git.baseUrl() + "/c/" + sha), null);
    }

    void push(CicdEvent... commits) {
        events.store(connection, List.of(commits), "webhook");
        run();
    }

    UUID deploy(String id, String env, String sha, OffsetDateTime at) {
        events.store(connection, List.of(new CicdEvent("deployment", "deployment:" + ext + ":" + id + ":success", ext, null, null, null,
                new CicdEvent.Deployment(id, sha, null, env, "success", git.baseUrl() + "/g/first-" + ext + "/-/jobs/" + id, at), null)), "webhook");
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

    int decided(UUID d) {
        return db.fetchCount(CICD_FIRST_DEPLOY_CHECKS, CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d), CICD_FIRST_DEPLOY_CHECKS.RESULT.isNotNull());
    }

    long requests(String endpoint) {
        return git.requests().stream().filter(r -> r.path().endsWith("/repository/" + endpoint)).count();
    }

    /** A, then n commits in a line each naming the work package, then an unlinked tip that is deployed. Returns the tip. */
    String linkedLine(int n, long wp) {
        graph.commit(A, "시작");
        var pushed = new ArrayList<CicdEvent>();
        pushed.add(commit(A, "시작", T0));
        String prev = A;
        for (int i = 1; i <= n; i++) {
            graph.commit(graph.sha(i), "고침 " + i, prev);
            pushed.add(commit(graph.sha(i), "고침 " + i + " #" + wp, T0.plusMinutes(i)));
            prev = graph.sha(i);
        }
        graph.commit(M, "묶음", prev);
        pushed.add(commit(M, "묶음", T0.plusMinutes(n + 1)));
        push(pushed.toArray(CicdEvent[]::new));
        return M;
    }

    @Test void firstDeploymentChecksLinkedCommits() throws Exception {
        long wpB = newWorkPackage("로그인"), wpC = newWorkPackage("결제"), wpH = newWorkPackage("핫픽스");
        graph.commit(A, "시작").commit(B, "로그인 고침", A).commit(C, "결제 붙임", B).commit(H, "핫픽스", A);
        push(commit(A, "시작", T0), commit(B, "로그인 고침 #" + wpB, T0.plusMinutes(1)), commit(C, "결제 붙임 #" + wpC, T0.plusMinutes(2)),
                commit(H, "핫픽스 #" + wpH, T0.plusMinutes(3)));
        var d = deploy("1", "dev", C, T0.plusHours(1));
        assertThat(inclusion(d)).isEqualTo("first");
        assertThat(in("개발")).containsExactlyInAnyOrder(B, C);   // H is on another line, A is linked to nothing
        assertThat(requests("merge_base")).isEqualTo(2);   // B and H; C is the deployed commit itself
        assertThat(requests("compare")).isZero();
        assertThat(db.select(CICD_FIRST_DEPLOY_CHECKS.RESULT).from(CICD_FIRST_DEPLOY_CHECKS).where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d))
                .fetch(CICD_FIRST_DEPLOY_CHECKS.RESULT)).containsExactlyInAnyOrder("in", "out");
    }

    @Test void capsAtTwoHundredCandidates() throws Exception {
        long wp = newWorkPackage("큰 작업");
        var tip = linkedLine(230, wp);
        var d = deploy("1", "dev", tip, T0.plusHours(5));
        var checked = db.select(CICD_COMMITS.SHA).from(CICD_FIRST_DEPLOY_CHECKS).join(CICD_COMMITS).on(CICD_COMMITS.ID.eq(CICD_FIRST_DEPLOY_CHECKS.COMMIT_ID))
                .where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d)).fetch(CICD_COMMITS.SHA);
        assertThat(checked).hasSize(200);
        for (int i = 1; i <= 30; i++) assertThat(checked).doesNotContain(graph.sha(i));   // the oldest 30 are not checked
        assertThat(db.select(CICD_DEPLOYMENTS.FIRST_CUTOFF_AT).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1().toInstant())
                .isEqualTo(T0.plusMinutes(31).toInstant());   // the 200th newest linked commit
        for (int i = 0; i < 3; i++) run();
        assertThat(inclusion(d)).isEqualTo("first");
        assertThat(requests("merge_base")).isEqualTo(200);
        assertThat(in("개발")).hasSize(201);   // the tip and the 200 checked
    }

    @Test void fiftyCallsPerRepositoryPerRun() throws Exception {
        long wp = newWorkPackage("많은 커밋");
        var tip = linkedLine(120, wp);
        var d = deploy("1", "dev", tip, T0.plusHours(3));
        assertThat(decided(d)).isEqualTo(50);
        assertThat(inclusion(d)).isEqualTo("checking");
        run();
        assertThat(decided(d)).isEqualTo(100);
        assertThat(inclusion(d)).isEqualTo("checking");
        run();
        assertThat(decided(d)).isEqualTo(120);
        assertThat(inclusion(d)).isEqualTo("first");
        assertThat(requests("merge_base")).isEqualTo(120);
        assertThat(in("개발")).hasSize(121);
    }

    @Test void laterDeploymentWaitsForTheFirstCheck() throws Exception {
        long wp = newWorkPackage("기다림");
        var tip = linkedLine(120, wp);
        var first = deploy("1", "dev", tip, T0.plusHours(3));
        String next = "f0000000000000000000000000000000000000f6";
        graph.commit(next, "다음", tip);
        push(commit(next, "다음", T0.plusHours(3).plusMinutes(1)));   // decides 50 more of the first deployment's candidates
        var second = deploy("2", "dev", next, T0.plusHours(4));
        assertThat(inclusion(first)).isEqualTo("first");   // the third run decided the last 20
        assertThat(inclusion(second)).isEqualTo("checking");   // the head was still being checked when the advance ran
        assertThat(requests("compare")).isZero();
        run();
        assertThat(inclusion(second)).isEqualTo("compared");
        assertThat(in("개발")).hasSize(122).contains(tip, next);
    }

    @Test void checkErrorsEndAsFailed() throws Exception {
        long wp = newWorkPackage("오류");
        graph.commit(A, "시작").commit(B, "로그인 고침", A).commit(C, "결제 붙임", B);
        push(commit(A, "시작", T0), commit(B, "로그인 고침 #" + wp, T0.plusMinutes(1)), commit(C, "결제 붙임", T0.plusMinutes(2)));
        git.respondWith("/api/v4/projects/" + ext + "/repository/merge_base", r -> new FakeGitServer.Reply(500, "{}"));
        var d = deploy("1", "dev", C, T0.plusHours(1));
        run();   // 15 seconds later: waiting for the 1-minute retry, not asked again
        assertThat(requests("merge_base")).isEqualTo(1);
        for (int i = 0; i < 3; i++) { clock = clock.plus(Duration.ofMinutes(9)); run(); }
        assertThat(inclusion(d)).isEqualTo("checking");   // four failures so far
        clock = clock.plus(Duration.ofMinutes(9));
        run();
        assertThat(requests("merge_base")).isEqualTo(5);
        assertThat(db.select(CICD_FIRST_DEPLOY_CHECKS.RESULT, CICD_FIRST_DEPLOY_CHECKS.ATTEMPTS).from(CICD_FIRST_DEPLOY_CHECKS)
                .where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d)).fetchOne())
                .satisfies(r -> { assertThat(r.value1()).isEqualTo("error"); assertThat(r.value2()).isEqualTo(5); });
        assertThat(inclusion(d)).isEqualTo("failed");
        assertThat(db.select(CICD_DEPLOYMENTS.CHECK_ERROR).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1()).isEqualTo("first_check");
        assertThat(in("개발")).containsExactly(C);
    }

    @Test void noTokenEndsTheCheckAtOnce() throws Exception {
        long wp = newWorkPackage("토큰 없음");
        graph.commit(A, "시작").commit(B, "로그인 고침", A).commit(C, "결제 붙임", B);
        push(commit(A, "시작", T0), commit(B, "로그인 고침 #" + wp, T0.plusMinutes(1)), commit(C, "결제 붙임", T0.plusMinutes(2)));
        f.send(admin, patch("/api/v1/admin/cicd/connections/" + connection), "{\"apiToken\":\"\"}").andExpect(status().isOk());
        var d = deploy("1", "dev", C, T0.plusHours(1));
        assertThat(inclusion(d)).isEqualTo("failed");
        assertThat(db.select(CICD_DEPLOYMENTS.CHECK_ERROR).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1()).isEqualTo("no_token");
        assertThat(db.select(CICD_FIRST_DEPLOY_CHECKS.RESULT).from(CICD_FIRST_DEPLOY_CHECKS).where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d))
                .fetch(CICD_FIRST_DEPLOY_CHECKS.RESULT)).containsExactly("error");
        assertThat(requests("merge_base")).isZero();
    }

    @Test void aliasAddedLaterReprocessesInOrder() throws Exception {
        long wp = newWorkPackage("별칭");
        graph.commit(A, "시작").commit(B, "로그인 고침", A).commit(C, "결제 붙임", B);
        push(commit(A, "시작", T0), commit(B, "로그인 고침 #" + wp, T0.plusMinutes(1)), commit(C, "결제 붙임", T0.plusMinutes(2)));
        var t1 = deploy("1", "qa-1", B, T0.plusHours(1));
        var t2 = deploy("2", "qa-1", C, T0.plusHours(2));
        assertThat(inclusion(t1)).isNull();   // no environment is called qa-1 yet
        assertThat(inclusion(t2)).isNull();
        var stg = db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS)
                .where(CICD_ENVIRONMENTS.PROJECT_ID.eq(UUID.fromString(f.projectId(admin, project))), CICD_ENVIRONMENTS.NAME.eq("스테이징")).fetchOne().value1();
        f.send(admin, patch("/api/v1/projects/" + project + "/cicd/environments/" + stg), "{\"aliases\":[\"stg\",\"staging\",\"qa-1\"]}")
                .andExpect(status().isOk());
        run();
        assertThat(inclusion(t1)).isEqualTo("first");
        assertThat(inclusion(t2)).isEqualTo("compared");
        assertThat(in("스테이징")).containsExactlyInAnyOrder(B, C);
        var t0 = deploy("0", "qa-1", A, T0.plusMinutes(30));   // earlier than both: only recorded
        assertThat(inclusion(t0)).isNull();
        assertThat(in("스테이징")).containsExactlyInAnyOrder(B, C);
    }

    @Test void mergeCommitOfALinkedMergeRequestCounts() throws Exception {
        long wp = newWorkPackage("MR만");
        graph.commit(A, "시작").commit(C, "Merge branch", A).commit(M, "다음 작업", C);
        push(commit(A, "시작", T0), commit(C, "Merge branch", T0.plusMinutes(1)), commit(M, "다음 작업", T0.plusMinutes(2)));
        events.store(connection, List.of(new CicdEvent("merge_request", "merge_request:" + ext + ":7", ext, null,
                new CicdEvent.MergeRequest(7, "결제 화면", "", "merged", "feature/" + wp + "-x", "main", "박준호", CicdFixture.JUNHO_EMAIL,
                        T0.plusMinutes(1), T0.plusMinutes(1), git.baseUrl() + "/g/first-" + ext + "/-/merge_requests/7", C))), "webhook");
        run();
        // the deployed commit comes after the merge: the merge commit is the only candidate, and it is in
        var d = deploy("1", "dev", M, T0.plusHours(1));
        assertThat(inclusion(d)).isEqualTo("first");
        assertThat(requests("merge_base")).isEqualTo(1);
        assertThat(in("개발")).containsExactlyInAnyOrder(C, M);
    }
}

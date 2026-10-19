package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_FIRST_DEPLOY_CHECKS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

/**
 * A code host that does not answer is asked once per processor run: the deployment that hit it backs off as usual, the
 * connection's other deployments and first-deployment questions wait untouched for the next run, and other connections go on.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdUnreachableHostIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String R = "a0000000000000000000000000000000000000a1", X = "b0000000000000000000000000000000000000b2";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    @Autowired CicdDeployments deployments;
    CicdFixture f;
    MockHttpSession admin;
    Side down, up;
    Instant clock = Instant.parse("2026-10-15T02:00:00Z");

    /** One connection with its own fake host, project and repository. */
    record Side(FakeGitServer git, CommitGraph graph, UUID connection, UUID repo, String project, String ext) {}

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        down = side(true);
        up = side(false);
    }

    @AfterEach void tearDown() {
        deployments.batch = CicdDeployments.BATCH;
        down.git().close();
        up.git().close();
    }

    Side side(boolean dead) throws Exception {
        var git = new FakeGitServer();
        var ext = String.valueOf(60000 + (int) (Math.random() * 9999));
        var graph = new CommitGraph();
        if (dead) {
            // the connection is dropped without an answer: the client fails at once with "request failed"
            git.respondWith("/", r -> { throw new IllegalStateException("dropped for the test"); });
        } else {
            git.respondWith("/api/v4/projects/" + ext + "/repository/compare", r -> graph.gitlabCompare(r.query()));
            git.respondWith("/api/v4/projects/" + ext + "/repository/merge_base", r -> graph.gitlabMergeBase(r.query()));
        }
        var connection = UUID.fromString(f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-unreachable")[0]);
        var project = f.newProject(admin, "work_packages", "cicd");
        f.allow(admin, connection.toString(), project);
        var repo = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, repo).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, ext).set(CICD_REPOSITORIES.FULL_PATH, "g/host-" + ext)
                .set(CICD_REPOSITORIES.WEB_URL, git.baseUrl() + "/g/host-" + ext)
                .set(CICD_REPOSITORIES.PROJECT_ID, UUID.fromString(f.projectId(admin, project))).execute();
        return new Side(git, graph, connection, repo, project, ext);
    }

    void run() { processor.run(clock); clock = clock.plusSeconds(15); }

    void storeDeployment(Side s, String id, String env, String sha, OffsetDateTime at) {
        events.store(s.connection(), List.of(new CicdEvent("deployment", "deployment:" + s.ext() + ":" + id + ":success", s.ext(), null, null, null,
                new CicdEvent.Deployment(id, sha, null, env, "success", s.git().baseUrl() + "/g/host-" + s.ext() + "/-/jobs/" + id, at), null)), "webhook");
    }

    CicdEvent commit(Side s, String sha, String message, OffsetDateTime at) {
        return new CicdEvent("commit", "commit:" + s.ext() + ":" + sha, s.ext(),
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, at, s.git().baseUrl() + "/c/" + sha), null);
    }

    long newWorkPackage(Side s, String subject) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + s.project() + "/work-packages"),
                "{\"subject\":\"" + subject + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    UUID deployment(Side s, String id) {
        return db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(s.repo()), CICD_DEPLOYMENTS.EXTERNAL_ID.eq(id))
                .fetchOne().value1();
    }

    /** Calls to the host: the HTTP client sends a GET once more when the connection drops before any answer, so equal requests count once. */
    long calls(Side s) {
        return s.git().requests().stream().map(r -> r.path() + "?" + r.query()).distinct().count();
    }

    String inclusion(UUID d) {
        return db.select(CICD_DEPLOYMENTS.INCLUSION).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1();
    }

    @Test void aDeadHostIsAskedOnceWhileDeploymentsAdvance() {
        up.graph().commit(R, "시작").commit(X, "다음", R);
        // the first deployments need no question: nothing is linked
        // each environment starts from its own commit, so the three compare calls differ
        var envs = List.of("dev", "stg", "prod");
        for (int i = 0; i < envs.size(); i++) storeDeployment(down, "1-" + envs.get(i), envs.get(i), down.graph().sha(i + 1), T0);
        storeDeployment(up, "1", "dev", R, T0.plusMinutes(5));
        run();
        assertThat(down.git().requests()).isEmpty();
        // three deployments of the dead host wait to be compared, ahead of the healthy one
        for (var env : List.of("dev", "stg", "prod")) storeDeployment(down, "2-" + env, env, X, T0.plusHours(1));
        storeDeployment(up, "2", "dev", X, T0.plusHours(2));
        run();
        assertThat(calls(down)).isEqualTo(1);
        assertThat(inclusion(deployment(up, "2"))).isEqualTo("compared");
        var waiting = db.select(CICD_DEPLOYMENTS.INCLUSION, CICD_DEPLOYMENTS.ATTEMPTS, CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT).from(CICD_DEPLOYMENTS)
                .where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(down.repo()), CICD_DEPLOYMENTS.EXTERNAL_ID.startsWith("2-")).fetch();
        assertThat(waiting).hasSize(3).allSatisfy(r -> assertThat(r.value1()).isEqualTo("checking"));
        // the one that hit the host backs off; the other two were left as they were
        assertThat(waiting.stream().filter(r -> r.value2() == 1 && r.value3() != null)).hasSize(1);
        assertThat(waiting.stream().filter(r -> r.value2() == 0 && r.value3() == null)).hasSize(2);
    }

    @Test void aDeadHostWithMoreThanABatchDoesNotStarveOtherConnections() {
        deployments.batch = 2;   // the dead host's due deployments fill more than one batch
        up.graph().commit(R, "시작").commit(X, "다음", R);
        var envs = List.of("dev", "stg", "prod");
        for (int i = 0; i < envs.size(); i++) storeDeployment(down, "1-" + envs.get(i), envs.get(i), down.graph().sha(i + 1), T0);
        storeDeployment(up, "1", "dev", R, T0.plusMinutes(5));
        run();
        assertThat(down.git().requests()).isEmpty();
        assertThat(inclusion(deployment(up, "1"))).isEqualTo("first");
        // three due deployments of the dead host, ahead of the healthy one: more than one batch
        for (var env : envs) storeDeployment(down, "2-" + env, env, X, T0.plusHours(1));
        storeDeployment(up, "2", "dev", X, T0.plusHours(2));
        run();
        assertThat(calls(down)).isEqualTo(1);
        assertThat(inclusion(deployment(up, "2"))).isEqualTo("compared");
        var waiting = db.select(CICD_DEPLOYMENTS.ATTEMPTS).from(CICD_DEPLOYMENTS)
                .where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(down.repo()), CICD_DEPLOYMENTS.EXTERNAL_ID.startsWith("2-")).fetch(CICD_DEPLOYMENTS.ATTEMPTS);
        assertThat(waiting).containsExactlyInAnyOrder(1, 0, 0);
    }

    @Test void aDeadHostIsAskedOnceWhileFirstDeploymentsAreChecked() throws Exception {
        long wpDown = newWorkPackage(down, "죽은 서버"), wpUp = newWorkPackage(up, "살아 있는 서버");
        // the dead host's repository: three linked commits under the deployed tip; the healthy one: one
        var pushed = new ArrayList<CicdEvent>();
        pushed.add(commit(down, R, "시작", T0));
        for (int i = 1; i <= 3; i++) pushed.add(commit(down, down.graph().sha(i), "고침 " + i + " #" + wpDown, T0.plusMinutes(i)));
        pushed.add(commit(down, X, "묶음", T0.plusMinutes(4)));
        events.store(down.connection(), pushed, "webhook");
        up.graph().commit(R, "시작").commit(up.graph().sha(1), "고침", R).commit(X, "묶음", up.graph().sha(1));
        events.store(up.connection(), List.of(commit(up, R, "시작", T0), commit(up, up.graph().sha(1), "고침 #" + wpUp, T0.plusMinutes(1)),
                commit(up, X, "묶음", T0.plusMinutes(2))), "webhook");
        storeDeployment(down, "1", "dev", X, T0.plusHours(1));
        storeDeployment(up, "1", "dev", X, T0.plusHours(2));
        run();
        assertThat(calls(down)).isEqualTo(1);
        assertThat(inclusion(deployment(up, "1"))).isEqualTo("first");
        var d = deployment(down, "1");
        assertThat(inclusion(d)).isEqualTo("checking");
        var questions = db.select(CICD_FIRST_DEPLOY_CHECKS.RESULT, CICD_FIRST_DEPLOY_CHECKS.ATTEMPTS, CICD_FIRST_DEPLOY_CHECKS.NEXT_ATTEMPT_AT)
                .from(CICD_FIRST_DEPLOY_CHECKS).where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d)).fetch();
        assertThat(questions).hasSize(3).allSatisfy(r -> assertThat(r.value1()).isNull());
        assertThat(questions.stream().filter(r -> r.value2() == 1 && r.value3() != null)).hasSize(1);
        assertThat(questions.stream().filter(r -> r.value2() == 0 && r.value3() == null)).hasSize(2);
    }
}

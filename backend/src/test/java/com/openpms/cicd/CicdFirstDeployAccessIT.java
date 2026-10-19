package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_FIRST_DEPLOY_CHECKS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The ancestor check answers 404 (GitHub also 422) both for a commit that is gone (a force push) and for a repository the
 * token can no longer read. The repository is asked once per repository per run: visible, the commit is "out"; not visible
 * (or the question fails), the answer is a failure like any other, so the first deployment cannot end "first" by mistake.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdFirstDeployAccessIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String A = "a0000000000000000000000000000000000000a1", B = "b0000000000000000000000000000000000000b2",
            M = "e0000000000000000000000000000000000000e5",
            // linked commits the host no longer has
            G1 = "f0000000000000000000000000000000000000f1", G2 = "f0000000000000000000000000000000000000f2";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    CicdFixture f;
    MockHttpSession admin;
    FakeGitServer git;
    CommitGraph graph;
    UUID connection, repo;
    String kind, project, ext;
    Instant clock = Instant.parse("2026-10-15T02:00:00Z");

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        ext = String.valueOf(70000 + (int) (Math.random() * 9999));
        graph = new CommitGraph().commit(A, "시작").commit(B, "로그인 고침", A).commit(M, "묶음", B);
    }

    @AfterEach void tearDown() { git.close(); }

    /** A connection of this kind whose host answers the ancestor check from the graph; the repository itself only when visible. */
    void connect(String kind, boolean visible) throws Exception {
        this.kind = kind;
        if (kind.equals("gitlab")) {
            if (visible) {
                git.respondWith("/api/v4/projects/" + ext + "/repository/merge_base", r -> graph.gitlabMergeBase(r.query()));
                git.respond("/api/v4/projects/" + ext, 200, "{\"id\":" + ext + ",\"path_with_namespace\":\"g/access\",\"web_url\":\"" + git.baseUrl() + "/g/access\"}");
            }
        } else if (visible) {
            git.respondWith("/api/v3/repositories/" + ext + "/compare/", r -> graph.githubCompare(r.path(), r.query()));
            git.respond("/api/v3/repositories/" + ext, 200, "{\"id\":" + ext + ",\"full_name\":\"g/access\",\"html_url\":\"" + git.baseUrl() + "/g/access\"}");
        }
        // not visible: every path of the repository answers 404, as GitHub and GitLab do for a token that lost access
        connection = UUID.fromString(f.newConnection(admin, kind, git.baseUrl(), kind.equals("gitlab") ? "glpat-access" : "ghp-access")[0]);
        project = f.newProject(admin, "work_packages", "cicd");
        f.allow(admin, connection.toString(), project);
        repo = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, repo).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, ext).set(CICD_REPOSITORIES.FULL_PATH, "g/access")
                .set(CICD_REPOSITORIES.WEB_URL, git.baseUrl() + "/g/access")
                .set(CICD_REPOSITORIES.PROJECT_ID, UUID.fromString(f.projectId(admin, project))).execute();
        long wp = f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"접근 확인\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
        // B is on the deployed line; G1 and G2 were pushed, linked and later removed from the host (a force push)
        events.store(connection, List.of(commit(A, "시작", T0), commit(B, "로그인 고침 #" + wp, T0.plusMinutes(1)),
                commit(G1, "사라진 고침 #" + wp, T0.plusMinutes(2)), commit(G2, "사라진 고침 2 #" + wp, T0.plusMinutes(3)),
                commit(M, "묶음", T0.plusMinutes(4))), "webhook");
        run();
    }

    void run() { processor.run(clock); clock = clock.plusSeconds(15); }

    CicdEvent commit(String sha, String message, OffsetDateTime at) {
        return new CicdEvent("commit", "commit:" + ext + ":" + sha, ext,
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, at, git.baseUrl() + "/c/" + sha), null);
    }

    UUID deploy() {
        events.store(connection, List.of(new CicdEvent("deployment", "deployment:" + ext + ":1:success", ext, null, null, null,
                new CicdEvent.Deployment("1", M, null, "dev", "success", git.baseUrl() + "/g/access/-/jobs/1", T0.plusHours(1)), null)), "webhook");
        run();
        return db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo), CICD_DEPLOYMENTS.EXTERNAL_ID.eq("1"))
                .fetchOne().value1();
    }

    String inclusion(UUID d) {
        return db.select(CICD_DEPLOYMENTS.INCLUSION).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1();
    }

    /** Each checked commit's result ("-" while open) and attempts. */
    Map<String, String> questions(UUID d) {
        return db.select(CICD_COMMITS.SHA, CICD_FIRST_DEPLOY_CHECKS.RESULT, CICD_FIRST_DEPLOY_CHECKS.ATTEMPTS).from(CICD_FIRST_DEPLOY_CHECKS)
                .join(CICD_COMMITS).on(CICD_COMMITS.ID.eq(CICD_FIRST_DEPLOY_CHECKS.COMMIT_ID))
                .where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d))
                .fetch().stream().collect(Collectors.toMap(r -> r.value1(), r -> (r.value2() == null ? "-" : r.value2()) + "/" + r.value3()));
    }

    /** Requests for the repository itself (the probe), not for anything under it. */
    long probes() {
        var path = kind.equals("gitlab") ? "/api/v4/projects/" + ext : "/api/v3/repositories/" + ext;
        return git.requests().stream().filter(r -> r.path().equals(path)).count();
    }

    @ParameterizedTest @ValueSource(strings = {"gitlab", "github"})
    void aCommitGoneFromAVisibleRepositoryIsOut(String kind) throws Exception {
        connect(kind, true);
        var d = deploy();
        assertThat(inclusion(d)).isEqualTo("first");
        assertThat(questions(d)).containsOnly(Map.entry(B, "in/0"), Map.entry(G1, "out/0"), Map.entry(G2, "out/0"));
        assertThat(probes()).isEqualTo(1);   // two commits gone, the repository asked once in the run
    }

    @ParameterizedTest @ValueSource(strings = {"gitlab", "github"})
    void aRepositoryTheTokenCannotSeeFailsTheQuestions(String kind) throws Exception {
        connect(kind, false);
        var d = deploy();
        assertThat(inclusion(d)).isEqualTo("checking");   // not "first": nothing could be checked
        assertThat(questions(d)).containsOnly(Map.entry(B, "-/1"), Map.entry(G1, "-/1"), Map.entry(G2, "-/1"));
        assertThat(probes()).isEqualTo(1);
        assertThat(db.select(CICD_FIRST_DEPLOY_CHECKS.NEXT_ATTEMPT_AT).from(CICD_FIRST_DEPLOY_CHECKS)
                .where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(d)).fetch(CICD_FIRST_DEPLOY_CHECKS.NEXT_ATTEMPT_AT)).doesNotContainNull();
        clock = clock.plusSeconds(61);   // the 1-minute retry: asked again, the repository once more in this run
        run();
        assertThat(questions(d)).containsOnly(Map.entry(B, "-/2"), Map.entry(G1, "-/2"), Map.entry(G2, "-/2"));
        assertThat(probes()).isEqualTo(2);
    }
}

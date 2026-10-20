package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_RELEASES;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * Release records written by the deployment processing (design 4.4, 보완 8·9): a production deployment that brings commits in
 * is one read-only record of the work packages it carried, with each one's verification on the default source environment.
 * The setup is CicdDeploymentsIT's: A ← B ← C, H ← A; B carries #wp, C carries #wp2.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdReleasesIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String A = "a0000000000000000000000000000000000000a1", B = "b0000000000000000000000000000000000000b2",
            C = "c0000000000000000000000000000000000000c3", H = "d0000000000000000000000000000000000000d4",
            D = "f0000000000000000000000000000000000000f6", E = "e0000000000000000000000000000000000000e5";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    @Autowired CicdVerificationStates states;
    CicdFixture f;
    MockHttpSession admin;
    FakeGitServer git;
    CommitGraph graph;
    UUID connection, repo, projectId;
    String project, ext;
    long wp, wp2;
    Instant clock = Instant.parse("2026-10-15T02:00:00Z");

    record Release(UUID id, UUID environmentId, UUID sourceEnvironmentId, UUID deploymentId, OffsetDateTime deployedAt, boolean partial,
            boolean contentFailed, UUID projectId) {}

    record Item(UUID workPackageId, long number, String subject, UUID verificationId, String warning) {}

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        ext = String.valueOf(60000 + (int) (Math.random() * 9999));
        graph = new CommitGraph().commit(A, "시작").commit(B, "로그인 고침", A).commit(C, "결제 붙임", B).commit(H, "핫픽스", A);
        git.respondWith("/api/v4/projects/" + ext + "/repository/compare", r -> graph.gitlabCompare(r.query()));
        // first deployments are answered at once, so they do not sit in "checking" ahead of the next deployment (Ruling F2)
        git.respondWith("/api/v4/projects/" + ext + "/repository/merge_base", r -> graph.gitlabMergeBase(r.query()));
        connection = UUID.fromString(f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-release")[0]);
        project = f.newProject(admin, "work_packages", "cicd");
        f.allow(admin, connection.toString(), project);
        projectId = UUID.fromString(f.projectId(admin, project));
        repo = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, repo).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, ext).set(CICD_REPOSITORIES.FULL_PATH, "g/rel-" + ext)
                .set(CICD_REPOSITORIES.WEB_URL, git.baseUrl() + "/g/rel-" + ext).set(CICD_REPOSITORIES.PROJECT_ID, projectId).execute();
        wp = newWorkPackage("로그인 시험");
        wp2 = newWorkPackage("결제 시험");
        push(A, "시작", T0);
        push(B, "로그인 고침 #" + wp, T0.plusMinutes(1));
        push(C, "결제 붙임 #" + wp2, T0.plusMinutes(2));
        push(H, "핫픽스", T0.plusMinutes(3));
        run();
    }

    @AfterEach void tearDown() { git.close(); }

    long newWorkPackage(String subject) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"" + subject + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn()).get("number").asLong();
    }

    void run() { processor.run(clock); clock = clock.plusSeconds(15); }

    void push(String sha, String message, OffsetDateTime at) {
        events.store(connection, List.of(new CicdEvent("commit", "commit:" + ext + ":" + sha, ext,
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, at, git.baseUrl() + "/c/" + sha), null)), "webhook");
    }

    UUID deploy(String id, String env, String sha, OffsetDateTime at) {
        events.store(connection, List.of(new CicdEvent("deployment", "deployment:" + ext + ":" + id + ":success", ext, null, null, null,
                new CicdEvent.Deployment(id, sha, null, env, "success", git.baseUrl() + "/g/rel-" + ext + "/-/jobs/" + id, at), null)), "webhook");
        run();
        return db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo), CICD_DEPLOYMENTS.EXTERNAL_ID.eq(id)).fetchOne().value1();
    }

    String inclusion(UUID d) {
        return db.select(CICD_DEPLOYMENTS.INCLUSION).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1();
    }

    UUID env(String name) {
        return db.select(CICD_ENVIRONMENTS.ID).from(CICD_ENVIRONMENTS)
                .where(CICD_ENVIRONMENTS.PROJECT_ID.eq(projectId), CICD_ENVIRONMENTS.NAME.eq(name)).fetchOne().value1();
    }

    /** A second production environment after 운영, deployed to as "prod2". */
    UUID secondProduction() {
        return db.fetchOne("insert into cicd_environments (project_id, name, position, production, aliases) values (?, '운영2', 4, true, '{prod2}') returning id",
                projectId).get(0, UUID.class);
    }

    UUID wpId(long number) {
        return db.fetchOne("select id from work_packages where number = ?", number).get(0, UUID.class);
    }

    OffsetDateTime addedAt(String envName, String sha) {
        return db.fetchOne("select ec.added_at from cicd_environment_commits ec join cicd_commits c on c.id = ec.commit_id "
                + "where ec.environment_id = ? and c.repository_id = ? and c.sha = ?", env(envName), repo, sha).get(0, OffsetDateTime.class);
    }

    List<Release> releases() {
        return db.fetch("select id, environment_id, source_environment_id, deployment_id, deployed_at, partial, content_failed, project_id "
                + "from cicd_releases where repository_id = ? order by deployed_at, id", repo)
                .map(r -> new Release(r.get(0, UUID.class), r.get(1, UUID.class), r.get(2, UUID.class), r.get(3, UUID.class),
                        r.get(4, OffsetDateTime.class), r.get(5, Boolean.class), r.get(6, Boolean.class), r.get(7, UUID.class)));
    }

    List<Item> items(UUID release) {
        return db.fetch("select work_package_id, number, subject, verification_id, warning from cicd_release_items where release_id = ? order by number", release)
                .map(r -> new Item(r.get(0, UUID.class), r.get(1, Long.class), r.get(2, String.class), r.get(3, UUID.class), r.get(4, String.class)));
    }

    @Test void aProductionDeploymentThatAddsCommitsWritesARecord() {
        deploy("s1", "stg", C, T0.plusHours(1));
        deploy("p1", "prod", A, T0.plusHours(2));   // the first production deployment: no record
        var d = deploy("p2", "prod", C, T0.plusHours(3));
        assertThat(inclusion(d)).isEqualTo("compared");
        var all = releases();
        assertThat(all).hasSize(1);
        var r = all.get(0);
        assertThat(r.projectId()).isEqualTo(projectId);
        assertThat(r.environmentId()).isEqualTo(env("운영"));
        assertThat(r.sourceEnvironmentId()).isEqualTo(env("스테이징"));
        assertThat(r.deploymentId()).isEqualTo(d);
        assertThat(r.deployedAt().toInstant()).isEqualTo(T0.plusHours(3).toInstant());
        assertThat(r.partial()).isFalse();
        assertThat(r.contentFailed()).isFalse();
        assertThat(items(r.id())).containsExactly(
                new Item(wpId(wp), wp, "로그인 시험", null, "unverified"),
                new Item(wpId(wp2), wp2, "결제 시험", null, "unverified"));
    }

    @Test void itemsCarryTheCurrentVerificationAndWarning() {
        deploy("s1", "stg", C, T0.plusHours(1));
        var stg = env("스테이징");
        var passed = ReleaseFixture.verification(db, wp, stg, "passed", "", T0.plusMinutes(90), addedAt("스테이징", B));
        var failed = ReleaseFixture.verification(db, wp2, stg, "failed", "결제가 안 됨", T0.plusMinutes(90), addedAt("스테이징", C));
        deploy("p1", "prod", A, T0.plusHours(2));
        deploy("p2", "prod", C, T0.plusHours(3));
        assertThat(items(releases().get(0).id())).containsExactly(
                new Item(wpId(wp), wp, "로그인 시험", passed, null),
                new Item(wpId(wp2), wp2, "결제 시험", failed, "failed"));
    }

    @Test void aCommitThatCameInAfterThePassMakesTheItemStale() {
        graph.commit(D, "로그인 다시 고침", C);
        push(D, "로그인 다시 고침 #" + wp, T0.plusMinutes(4));
        run();
        deploy("s1", "stg", C, T0.plusHours(1));
        var passed = ReleaseFixture.verification(db, wp, env("스테이징"), "passed", "", T0.plusMinutes(90), addedAt("스테이징", B));
        deploy("p1", "prod", A, T0.plusHours(2));
        deploy("s2", "stg", D, T0.plusHours(3));   // wp's new commit comes into the source after the pass
        deploy("p2", "prod", C, T0.plusHours(4));
        var items = items(releases().get(0).id());
        assertThat(items).extracting(Item::number).containsExactly(wp, wp2);
        assertThat(items.get(0).verificationId()).isEqualTo(passed);
        assertThat(items.get(0).warning()).isEqualTo("stale");
    }

    @Test void theFirstDeploymentWritesNoRecord() {
        var d = deploy("p1", "prod", C, T0.plusHours(1));   // B and C are in (C itself, B by the first-deployment check)
        assertThat(inclusion(d)).isEqualTo("first");
        assertThat(releases()).isEmpty();
    }

    @Test void aRollbackWritesNoRecord() {
        deploy("p1", "prod", A, T0.plusHours(1));
        var forward = deploy("p2", "prod", C, T0.plusHours(2));
        var back = deploy("p3", "prod", A, T0.plusHours(3));   // B and C go out, nothing comes in
        assertThat(inclusion(back)).isEqualTo("compared");
        assertThat(releases()).extracting(Release::deploymentId).containsExactly(forward);
    }

    @Test void aRestartOfTheSameCommitWritesNoRecord() {   // Review Focus 4
        deploy("p1", "prod", A, T0.plusHours(1));
        var first = deploy("p2", "prod", C, T0.plusHours(2));
        var restart = deploy("p3", "prod", C, T0.plusHours(3));   // the same commit again: applied with nothing brought in
        assertThat(inclusion(restart)).isEqualTo("compared");
        assertThat(releases()).extracting(Release::deploymentId).containsExactly(first);
        assertThat(db.fetchCount(CICD_RELEASES, CICD_RELEASES.DEPLOYMENT_ID.eq(restart))).isZero();
    }

    @Test void otherEnvironmentsWriteNoRecord() {
        deploy("s1", "stg", A, T0.plusHours(1));
        deploy("s2", "stg", C, T0.plusHours(2));
        deploy("s3", "stg", H, T0.plusHours(3));
        deploy("d1", "dev", A, T0.plusHours(1));
        deploy("d2", "dev", C, T0.plusHours(2));
        assertThat(releases()).isEmpty();
    }

    @Test void twoProductionEnvironmentsEachRecord() {
        var second = secondProduction();
        deploy("p1", "prod", A, T0.plusHours(1));
        deploy("p2", "prod", C, T0.plusHours(2));
        deploy("q1", "prod2", A, T0.plusHours(1));
        deploy("q2", "prod2", C, T0.plusHours(2));
        assertThat(releases()).extracting(Release::environmentId).containsExactlyInAnyOrder(env("운영"), second);
        for (var r : releases()) assertThat(items(r.id())).extracting(Item::number).containsExactly(wp, wp2);
    }

    @Test void partialComparisonMarksTheRecord() {
        String prev = C;
        for (int i = 1; i <= 1001; i++) { graph.commit(graph.sha(i), "커밋 " + i, prev); prev = graph.sha(i); }
        deploy("p1", "prod", C, T0.plusHours(1));
        var big = deploy("p2", "prod", prev, T0.plusHours(2));
        assertThat(inclusion(big)).isEqualTo("partial");
        assertThat(releases()).singleElement().satisfies(r -> {
            assertThat(r.deploymentId()).isEqualTo(big);
            assertThat(r.partial()).isTrue();
            assertThat(r.contentFailed()).isFalse();
        });
    }

    @Test void aCompareThatGivesUpWritesAContentFailedRecord() {
        var second = secondProduction();
        deploy("p1", "prod", A, T0.plusHours(1));
        db.execute("update cicd_connections set active = false where id = ?", connection);
        var d = deploy("p2", "prod", C, T0.plusHours(2));
        assertThat(inclusion(d)).isEqualTo("failed");
        assertThat(db.select(CICD_DEPLOYMENTS.CHECK_ERROR).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(d)).fetchOne().value1()).isEqualTo("no_token");
        assertThat(releases()).singleElement().satisfies(r -> {
            assertThat(r.deploymentId()).isEqualTo(d);
            assertThat(r.environmentId()).isEqualTo(env("운영"));
            assertThat(r.contentFailed()).isTrue();
            assertThat(r.partial()).isFalse();
            assertThat(items(r.id())).isEmpty();
        });
        // a first deployment whose check failed (CicdFirstDeploy, the connection is off) writes nothing
        var first = deploy("q1", "prod2", C, T0.plusHours(2));
        run();
        assertThat(inclusion(first)).isEqualTo("failed");
        assertThat(releases()).extracting(Release::environmentId).doesNotContain(second);
    }

    @Test void aDeploymentThatKeepsBreakingWritesAContentFailedRecordUnlessItIsTheFirst() {   // Ruling F8: broken
        var second = secondProduction();
        deploy("p1", "prod", A, T0.plusHours(1));
        var c = db.select(CICD_COMMITS.ID).from(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo), CICD_COMMITS.SHA.eq(C)).fetchOne().value1();
        // the database refuses C in an environment: applying a deployment of C throws every time
        String trigger = "cicd_test_release_refuse_" + ext;
        db.execute("create or replace function " + trigger + "() returns trigger language plpgsql as $$ begin raise exception 'refused for the test'; end $$");
        db.execute("create trigger " + trigger + " before insert on cicd_environment_commits for each row when (new.commit_id = '" + c
                + "'::uuid) execute function " + trigger + "()");
        try {
            var d = deploy("p2", "prod", C, T0.plusHours(2));        // 운영 has a head (A): moved on by the fifth error
            var first = deploy("q1", "prod2", C, T0.plusHours(2));   // 운영2's first deployment: made the head, nothing known before
            for (int i = 0; i < 6; i++) { clock = clock.plus(Duration.ofMinutes(9)); run(); }
            assertThat(inclusion(d)).isEqualTo("failed");
            assertThat(inclusion(first)).isEqualTo("failed");
            assertThat(db.select(CICD_DEPLOYMENTS.CHECK_ERROR).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.in(d, first)).fetch(CICD_DEPLOYMENTS.CHECK_ERROR))
                    .containsOnly("error");   // gave up through broken, not a failed compare
            assertThat(releases()).singleElement().satisfies(r -> {
                assertThat(r.deploymentId()).isEqualTo(d);
                assertThat(r.contentFailed()).isTrue();
                assertThat(items(r.id())).isEmpty();
            });
            assertThat(releases()).extracting(Release::environmentId).doesNotContain(second);
        } finally {
            db.execute("drop trigger if exists " + trigger + " on cicd_environment_commits");
            db.execute("drop function if exists " + trigger + "()");
        }
    }

    @Test void recordsAreSnapshots() throws Exception {
        deploy("s1", "stg", C, T0.plusHours(1));
        var stg = env("스테이징");
        var passed = ReleaseFixture.verification(db, wp, stg, "passed", "", T0.plusMinutes(90), addedAt("스테이징", B));
        deploy("p1", "prod", A, T0.plusHours(2));
        deploy("p2", "prod", C, T0.plusHours(3));
        var release = releases().get(0).id();
        db.execute("update work_packages set subject = '바뀐 제목' where number = ?", wp);
        ReleaseFixture.verification(db, wp, stg, "failed", "다시 깨짐", T0.plusHours(5), addedAt("스테이징", B));
        f.send(admin, delete("/api/v1/work-packages/" + wp2), "").andExpect(status().is2xxSuccessful());
        assertThat(items(release)).containsExactly(
                new Item(wpId(wp), wp, "로그인 시험", passed, null),
                new Item(null, wp2, "결제 시험", null, "unverified"));
    }

    @Test void noSourceEnvironmentMakesEveryItemUnverified() {
        db.execute("update cicd_environments set position = 0 where id = ?", env("운영"));   // nothing before it
        ReleaseFixture.verification(db, wp, env("스테이징"), "passed", "", T0.plusMinutes(30), T0);
        deploy("p1", "prod", A, T0.plusHours(1));
        deploy("p2", "prod", C, T0.plusHours(2));
        var r = releases().get(0);
        assertThat(r.sourceEnvironmentId()).isNull();
        assertThat(items(r.id())).extracting(Item::warning).containsExactly("unverified", "unverified");
        assertThat(items(r.id())).extracting(Item::verificationId).containsOnlyNulls();
    }

    @Test void aKnownCommitAddedAgainKeepsItsAddedAt() {
        deploy("s1", "stg", C, T0.plusHours(1));
        var firstAdded = addedAt("스테이징", B);
        assertThat(firstAdded.toInstant()).isEqualTo(T0.plusHours(1).toInstant());
        ReleaseFixture.verification(db, wp, env("스테이징"), "passed", "", T0.plusMinutes(90), firstAdded);
        // a host that answers C..E with B again (B is already in the environment), and nothing the other way
        graph = new CommitGraph().commit(A, "시작").commit(C, "결제 붙임", A).commit(B, "로그인 고침", A).commit(E, "합침", C, B);
        push(E, "합침", T0.plusMinutes(5));
        var d = deploy("s2", "stg", E, T0.plusHours(2));
        assertThat(inclusion(d)).isEqualTo("compared");
        assertThat(addedAt("스테이징", B).toInstant()).isEqualTo(firstAdded.toInstant());
        var id = wpId(wp);
        var current = states.current(env("스테이징"), List.of(id)).get(id);
        assertThat(CicdVerificationStates.column(current, states.latestIn(env("스테이징"), List.of(id)).get(id))).isEqualTo("passed");
    }

    @Test void aCommitBackAfterARollbackGetsANewAddedAt() {
        deploy("s1", "stg", C, T0.plusHours(1));
        deploy("s2", "stg", A, T0.plusHours(2));   // B and C go out
        deploy("s3", "stg", C, T0.plusHours(3));   // and come back
        assertThat(addedAt("스테이징", B).toInstant()).isEqualTo(T0.plusHours(3).toInstant());
    }
}

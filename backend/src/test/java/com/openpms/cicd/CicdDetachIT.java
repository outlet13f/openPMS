package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_PIPELINES;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.WORK_PACKAGE_CICD_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Detaching a repository keeps its history (V28 detached_at); it can come back as the same row, here or on another project. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdDetachIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final String NO_MR = "이 프로젝트에 붙은 저장소의 MR/PR 주소가 아니거나 아직 받지 않은 MR/PR입니다.";
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    @Autowired CicdPoller poller;
    CicdFixture f;
    MockHttpSession admin;
    FakeGitServer git;
    String[] conn;
    int seq;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        git.respond("/api/v4/projects", 200, "cicd/gitlab-projects.json");
        git.respond("/api/v4/projects/4201", 200,
                "{\"id\":4201,\"path_with_namespace\":\"openpms-test/web\",\"web_url\":\"https://gitlab.example.com/openpms-test/web\"}");
        conn = f.newConnection(admin, "gitlab", git.baseUrl(), "glpat-token-1234");
    }

    @AfterEach void tearDown() {
        git.close();
    }

    private String project() throws Exception {
        return f.newProject(admin, "work_packages", "cicd");
    }

    private UUID attach(String project) throws Exception {
        return UUID.fromString(f.body(f.send(admin, post("/api/v1/projects/" + project + "/cicd/repositories"),
                "{\"connectionId\":\"" + conn[0] + "\",\"externalId\":\"4201\"}").andExpect(status().isCreated()).andReturn()).get("id").asString());
    }

    private void detach(String project, UUID repo) throws Exception {
        f.send(admin, delete("/api/v1/projects/" + project + "/cicd/repositories/" + repo), "").andExpect(status().isNoContent());
    }

    private long wp(String project) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"뗀 저장소 시험 " + UUID.randomUUID() + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn())
                .get("number").asLong();
    }

    private String sha() {
        return String.format("%040x", UUID.randomUUID().getMostSignificantBits() ^ (++seq)).replace(' ', '0');
    }

    private CicdEvent commit(String sha, String message) {
        return new CicdEvent("commit", "commit:4201:" + sha, "4201",
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, OffsetDateTime.now(), "https://gitlab.example.com/c/" + sha), null);
    }

    private CicdEvent mr(long iid, String title) {
        var now = OffsetDateTime.now();
        return new CicdEvent("merge_request", "merge_request:4201:" + iid + ":" + now, "4201", null,
                new CicdEvent.MergeRequest(iid, title, "", "open", "topic", "main", "박준호", CicdFixture.JUNHO_EMAIL, null, now,
                        "https://gitlab.example.com/mr/" + iid));
    }

    private void feed(CicdEvent... list) {
        events.store(UUID.fromString(conn[0]), List.of(list), "webhook");
        processor.run(Instant.now());
    }

    private int commits(UUID repo) {
        return db.fetchCount(CICD_COMMITS, CICD_COMMITS.REPOSITORY_ID.eq(repo));
    }

    private int mrs(UUID repo) {
        return db.fetchCount(CICD_MERGE_REQUESTS, CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo));
    }

    private int links(UUID repo) {
        return db.fetchCount(WORK_PACKAGE_CICD_LINKS.join(CICD_COMMITS).on(CICD_COMMITS.ID.eq(WORK_PACKAGE_CICD_LINKS.COMMIT_ID))
                .where(CICD_COMMITS.REPOSITORY_ID.eq(repo)))
                + db.fetchCount(WORK_PACKAGE_CICD_LINKS.join(CICD_MERGE_REQUESTS).on(CICD_MERGE_REQUESTS.ID.eq(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID))
                .where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)));
    }

    private UUID projectUuid(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne(PROJECTS.ID);
    }

    @Test void detachKeepsTheHistory() throws Exception {
        var p = project();
        f.allow(admin, conn[0], p);
        long n = wp(p);
        var repo = attach(p);
        feed(commit(sha(), "고침 #" + n), mr(7, "MR #" + n));
        var now = OffsetDateTime.now();
        db.insertInto(CICD_PIPELINES).set(CICD_PIPELINES.REPOSITORY_ID, repo).set(CICD_PIPELINES.EXTERNAL_ID, "1").set(CICD_PIPELINES.SHA, sha())
                .set(CICD_PIPELINES.STATUS, "success").set(CICD_PIPELINES.SOURCE, "gitlab").set(CICD_PIPELINES.UPDATED_AT, now).execute();
        var env = db.insertInto(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.PROJECT_ID, projectUuid(p)).set(CICD_ENVIRONMENTS.NAME, "시험")
                .set(CICD_ENVIRONMENTS.POSITION, 1).returning(CICD_ENVIRONMENTS.ID).fetchOne().getId();
        db.insertInto(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.REPOSITORY_ID, repo).set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, env)
                .set(CICD_DEPLOYMENTS.ENVIRONMENT_NAME, "시험").set(CICD_DEPLOYMENTS.EXTERNAL_ID, "1").set(CICD_DEPLOYMENTS.STATUS, "success")
                .set(CICD_DEPLOYMENTS.DEPLOYED_AT, now).set(CICD_DEPLOYMENTS.SOURCE, "gitlab").execute();
        assertThat(commits(repo)).isEqualTo(1);
        assertThat(links(repo)).isEqualTo(2);

        detach(p, repo);

        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repo)).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getDetachedAt()).isNotNull();
        assertThat(commits(repo)).isEqualTo(1);
        assertThat(mrs(repo)).isEqualTo(1);
        assertThat(links(repo)).isEqualTo(2);
        assertThat(db.fetchCount(CICD_PIPELINES, CICD_PIPELINES.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(db.fetchCount(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(db.fetchCount(CICD_ENVIRONMENTS, CICD_ENVIRONMENTS.ID.eq(env))).isEqualTo(1);
        var list = f.body(f.send(admin, get("/api/v1/projects/" + p + "/cicd/repositories"), "").andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("detachedAt").isNull()).isFalse();
        var tab = f.body(f.send(admin, get("/api/v1/work-packages/" + n + "/cicd"), "").andExpect(status().isOk()).andReturn());
        assertThat(tab.get("commits")).hasSize(1);
        assertThat(tab.get("mergeRequests")).hasSize(1);
    }

    @Test void detachedRepositoryTakesNothingNew() throws Exception {
        var p = project();
        f.allow(admin, conn[0], p);
        long n = wp(p);
        var repo = attach(p);
        feed(commit(sha(), "첫 커밋 #" + n));
        detach(p, repo);
        int asked = git.requests().size();
        var late = sha();
        var event = commit(late, "늦은 커밋 #" + n);
        events.store(UUID.fromString(conn[0]), List.of(event), "webhook");
        var stored = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(conn[0])), CICD_EVENTS.DELIVERY_KEY.eq(event.deliveryKey()))
                .fetchOne();
        assertThat(stored.getRepositoryId()).isNull();
        processor.run(Instant.now());
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(UUID.fromString(conn[0])), CICD_EVENTS.DELIVERY_KEY.eq(event.deliveryKey()))).isZero();
        assertThat(commits(repo)).isEqualTo(1);
        poller.poll(Instant.now());
        assertThat(git.requests()).hasSize(asked);
    }

    @Test void reattachContinuesTheSameRow() throws Exception {
        var p = project();
        f.allow(admin, conn[0], p);
        long n = wp(p);
        var repo = attach(p);
        feed(commit(sha(), "예전 커밋 #" + n));
        var old = db.selectFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.COMMIT_ID.isNotNull(),
                WORK_PACKAGE_CICD_LINKS.COMMIT_ID.in(db.select(CICD_COMMITS.ID).from(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo)))).fetchOne();
        f.send(admin, delete("/api/v1/work-packages/" + n + "/cicd/links/" + old.getId()), "").andExpect(status().isNoContent());
        detach(p, repo);
        int asked = git.requests().size();

        var res = f.body(f.send(admin, post("/api/v1/projects/" + p + "/cicd/repositories/" + repo + "/reattach"), "").andExpect(status().isOk()).andReturn());
        assertThat(res.get("id").asString()).isEqualTo(repo.toString());
        assertThat(res.get("detachedAt").isNull()).isTrue();
        assertThat(git.requests()).hasSize(asked);   // no call to the host
        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repo)).fetchOne();
        assertThat(row.getDetachedAt()).isNull();
        assertThat(row.getPollCursor()).isBetween(OffsetDateTime.now().minusDays(7).minusMinutes(1), OffsetDateTime.now().minusDays(7).plusMinutes(1));
        // the link dismissed before stays dismissed, the old commit is still linked (dismissed)
        var after = db.selectFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.ID.eq(old.getId())).fetchOne();
        assertThat(after.getDismissedAt()).isNotNull();
        // a new commit is linked again
        var fresh = sha();
        feed(commit(fresh, "新 커밋 #" + n));
        var created = db.selectFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.COMMIT_ID.eq(
                db.select(CICD_COMMITS.ID).from(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo), CICD_COMMITS.SHA.eq(fresh)))).fetchOne();
        assertThat(created).isNotNull();
        assertThat(created.getDismissedAt()).isNull();
        // not detached any more: a second reattach finds nothing detached
        f.send(admin, post("/api/v1/projects/" + p + "/cicd/repositories/" + repo + "/reattach"), "").andExpect(status().isNotFound());
    }

    @Test void attachingADetachedRepositoryElsewhereMovesIt() throws Exception {
        var a = project();
        var b = project();
        f.allow(admin, conn[0], a, b);
        long wpA = wp(a);
        var repo = attach(a);
        feed(commit(sha(), "A의 작업 #" + wpA));
        var wpAId = db.select(com.openpms.db.Tables.WORK_PACKAGES.ID).from(com.openpms.db.Tables.WORK_PACKAGES)
                .where(com.openpms.db.Tables.WORK_PACKAGES.NUMBER.eq(wpA)).fetchOne(com.openpms.db.Tables.WORK_PACKAGES.ID);
        var now = OffsetDateTime.now();
        var env = db.insertInto(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.PROJECT_ID, projectUuid(a)).set(CICD_ENVIRONMENTS.NAME, "시험")
                .set(CICD_ENVIRONMENTS.POSITION, 1).returning(CICD_ENVIRONMENTS.ID).fetchOne().getId();
        var dep = db.insertInto(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.REPOSITORY_ID, repo).set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, env)
                .set(CICD_DEPLOYMENTS.ENVIRONMENT_NAME, "시험").set(CICD_DEPLOYMENTS.EXTERNAL_ID, "1").set(CICD_DEPLOYMENTS.STATUS, "success")
                .set(CICD_DEPLOYMENTS.DEPLOYED_AT, now).set(CICD_DEPLOYMENTS.SOURCE, "gitlab").returning(CICD_DEPLOYMENTS.ID).fetchOne().getId();
        var headSha = sha();
        db.insertInto(CICD_ENVIRONMENT_HEADS).set(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID, repo).set(CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID, env)
                .set(CICD_ENVIRONMENT_HEADS.SHA, headSha).set(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID, dep).set(CICD_ENVIRONMENT_HEADS.DEPLOYED_AT, now).execute();
        db.insertInto(CICD_ENVIRONMENT_COMMITS).set(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID, env).set(CICD_ENVIRONMENT_COMMITS.REPOSITORY_ID, repo)
                .set(CICD_ENVIRONMENT_COMMITS.COMMIT_ID, db.select(CICD_COMMITS.ID).from(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo)).limit(1).fetchOne(CICD_COMMITS.ID))
                .set(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID, dep).execute();
        db.insertInto(CICD_PIPELINES).set(CICD_PIPELINES.REPOSITORY_ID, repo).set(CICD_PIPELINES.EXTERNAL_ID, "1").set(CICD_PIPELINES.SHA, sha())
                .set(CICD_PIPELINES.STATUS, "success").set(CICD_PIPELINES.SOURCE, "gitlab").set(CICD_PIPELINES.UPDATED_AT, now).execute();
        detach(a, repo);
        var search = f.body(f.send(admin, get("/api/v1/projects/" + b + "/cicd/connections/" + conn[0] + "/search?q=web"), "")
                .andExpect(status().isOk()).andReturn());
        assertThat(search.get(0).has("attachedProject")).isFalse();
        var again = attach(b);
        assertThat(again).isEqualTo(repo);
        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repo)).fetchOne();
        assertThat(row.getProjectId()).isEqualTo(projectUuid(b));
        assertThat(row.getDetachedAt()).isNull();
        assertThat(db.fetchCount(CICD_REPOSITORIES, CICD_REPOSITORIES.CONNECTION_ID.eq(UUID.fromString(conn[0])))).isEqualTo(1);
        // the link made on A's work package stays there
        assertThat(links(repo)).isEqualTo(1);
        assertThat(db.fetchCount(WORK_PACKAGE_CICD_LINKS, WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(wpAId))).isEqualTo(1);
        // the old project's environment state is gone, the history stays
        assertThat(db.fetchCount(CICD_ENVIRONMENT_HEADS, CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(repo))).isZero();
        assertThat(db.fetchCount(CICD_ENVIRONMENT_COMMITS, CICD_ENVIRONMENT_COMMITS.REPOSITORY_ID.eq(repo))).isZero();
        assertThat(db.fetchCount(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(db.fetchCount(CICD_PIPELINES, CICD_PIPELINES.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(commits(repo)).isEqualTo(1);
        var listA = f.body(f.send(admin, get("/api/v1/projects/" + a + "/cicd/repositories"), "").andExpect(status().isOk()).andReturn());
        assertThat(listA).isEmpty();
        // attached now: a third project gets the usual 409
        var c = project();
        f.allow(admin, conn[0], a, b, c);
        f.send(admin, post("/api/v1/projects/" + c + "/cicd/repositories"), "{\"connectionId\":\"" + conn[0] + "\",\"externalId\":\"4201\"}")
                .andExpect(status().isConflict());
    }

    @Test void reattachNeedsTheConnectionToAllowTheProject() throws Exception {
        var a = project();
        var b = project();
        f.allow(admin, conn[0], a, b);
        var repo = attach(a);
        // a row that is not detached cannot be reattached
        f.send(admin, post("/api/v1/projects/" + a + "/cicd/repositories/" + repo + "/reattach"), "").andExpect(status().isNotFound());
        detach(a, repo);
        f.allow(admin, conn[0], b);   // only a detached repository on A: the list may drop it
        var res = f.send(admin, post("/api/v1/projects/" + a + "/cicd/repositories/" + repo + "/reattach"), "")
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("연동을 찾을 수 없습니다.");
        assertThat(db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repo)).fetchOne().getDetachedAt()).isNotNull();
        // another project's repository id is not found either
        f.send(admin, post("/api/v1/projects/" + b + "/cicd/repositories/" + repo + "/reattach"), "").andExpect(status().isNotFound());
    }

    @Test void manualLinkSkipsDetachedRepositories() throws Exception {
        var p = project();
        f.allow(admin, conn[0], p);
        long n = wp(p);
        long other = wp(p);
        var repo = attach(p);
        feed(mr(11, "MR #" + other));
        var url = "https://gitlab.example.com/mr/11";
        f.send(admin, post("/api/v1/work-packages/" + n + "/cicd/links"), "{\"url\":\"" + url + "\"}").andExpect(status().isCreated());
        db.deleteFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.in(db.select(com.openpms.db.Tables.WORK_PACKAGES.ID)
                .from(com.openpms.db.Tables.WORK_PACKAGES).where(com.openpms.db.Tables.WORK_PACKAGES.NUMBER.eq(n)))).execute();
        detach(p, repo);
        var res = f.send(admin, post("/api/v1/work-packages/" + n + "/cicd/links"), "{\"url\":\"" + url + "\"}")
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains(NO_MR);
    }

    @Test void auditLines() throws Exception {
        var a = project();
        var b = project();
        f.allow(admin, conn[0], a, b);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("openpms.audit");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        UUID repo;
        try {
            repo = attach(a);
            detach(a, repo);
            f.send(admin, post("/api/v1/projects/" + a + "/cicd/repositories/" + repo + "/reattach"), "").andExpect(status().isOk());
            detach(a, repo);
            attach(a);   // the same project through the attach endpoint: reattached
            detach(a, repo);
            attach(b);   // another project: moved
        } finally {
            logger.detachAppender(appender);
        }
        var lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        var admins = db.select(com.openpms.db.Tables.USERS.ID).from(com.openpms.db.Tables.USERS)
                .where(com.openpms.db.Tables.USERS.EMAIL.eq(CicdFixture.ADMIN)).fetchOne(com.openpms.db.Tables.USERS.ID);
        var aId = projectUuid(a);
        var bId = projectUuid(b);
        assertThat(lines).anyMatch(m -> m.equals("cicd.repository.detached by=" + admins + " project=" + aId + " repository=" + repo));
        assertThat(lines.stream().filter(m -> m.startsWith("cicd.repository.detached ")).count()).isEqualTo(3);
        assertThat(lines.stream().filter(m -> m.equals("cicd.repository.reattached by=" + admins + " project=" + aId + " repository=" + repo)).count())
                .isEqualTo(2);
        assertThat(lines).anyMatch(m -> m.equals("cicd.repository.moved by=" + admins + " from=" + aId + " to=" + bId + " repository=" + repo));
    }
}

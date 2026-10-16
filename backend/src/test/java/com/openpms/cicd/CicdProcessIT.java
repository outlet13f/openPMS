package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.WORK_PACKAGE_CICD_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
class CicdProcessIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
    @Autowired CicdLinker linker;
    CicdFixture f;
    MockHttpSession admin;
    UUID connection;
    int seq;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        connection = UUID.fromString(f.newConnection(admin, "gitlab", "https://gitlab.example.com", null)[0]);
    }

    private UUID projectId(String identifier) {
        return db.select(com.openpms.db.Tables.PROJECTS.ID).from(com.openpms.db.Tables.PROJECTS)
                .where(com.openpms.db.Tables.PROJECTS.IDENTIFIER.eq(identifier)).fetchOne().value1();
    }

    private String newProject() throws Exception {
        return f.newProject(admin, "work_packages", "cicd");
    }

    private String newSubProject(String parent) throws Exception {
        var identifier = "cd-" + UUID.randomUUID().toString().substring(0, 8);
        f.send(admin, post("/api/v1/projects"), "{\"name\":\"하위 " + identifier + "\",\"identifier\":\"" + identifier + "\",\"parentId\":\""
                + projectId(parent) + "\"}").andExpect(status().isCreated());
        return identifier;
    }

    /** Creates a work package in the project and returns its number. */
    private long wp(String project) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"연동 시험 " + UUID.randomUUID() + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn())
                .get("number").asLong();
    }

    private UUID attach(String project) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, id).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, "x-" + id).set(CICD_REPOSITORIES.FULL_PATH, "g/" + id)
                .set(CICD_REPOSITORIES.WEB_URL, "https://gitlab.example.com/g/" + id).set(CICD_REPOSITORIES.PROJECT_ID, projectId(project))
                .execute();
        return id;
    }

    private String externalId(UUID repo) {
        return db.select(CICD_REPOSITORIES.EXTERNAL_ID).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repo)).fetchOne().value1();
    }

    private String sha() {
        return String.format("%040x", UUID.randomUUID().getMostSignificantBits() ^ (++seq)).replace(' ', '0');
    }

    private CicdEvent commit(UUID repo, String sha, String message, String email) {
        return new CicdEvent("commit", "commit:" + repo + ":" + sha, externalId(repo),
                new CicdEvent.Commit(sha, message.lines().findFirst().orElse(""), message, "박준호", email, OffsetDateTime.now(),
                        "https://gitlab.example.com/c/" + sha), null);
    }

    private CicdEvent mr(UUID repo, long iid, String state, String title, String branch, OffsetDateTime updatedAt) {
        return new CicdEvent("merge_request", "merge_request:" + repo + ":" + iid + ":" + updatedAt, externalId(repo), null,
                new CicdEvent.MergeRequest(iid, title, "", state, branch, "main", "박준호", CicdFixture.JUNHO_EMAIL,
                        state.equals("merged") ? updatedAt : null, updatedAt, "https://gitlab.example.com/mr/" + iid));
    }

    private int linksOf(UUID repo) {
        return db.fetchCount(WORK_PACKAGE_CICD_LINKS.join(CICD_COMMITS).on(CICD_COMMITS.ID.eq(WORK_PACKAGE_CICD_LINKS.COMMIT_ID))
                .where(CICD_COMMITS.REPOSITORY_ID.eq(repo)))
                + db.fetchCount(WORK_PACKAGE_CICD_LINKS.join(CICD_MERGE_REQUESTS).on(CICD_MERGE_REQUESTS.ID.eq(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID))
                .where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)));
    }

    private List<com.openpms.db.tables.records.WorkPackageCicdLinksRecord> mrLinksOf(UUID repo) {
        return db.selectFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID.in(
                db.select(CICD_MERGE_REQUESTS.ID).from(CICD_MERGE_REQUESTS).where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)))).fetch();
    }

    private String eventStatus(String deliveryKey) {
        return db.select(CICD_EVENTS.STATUS).from(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(connection), CICD_EVENTS.DELIVERY_KEY.eq(deliveryKey))
                .fetchOne().value1();
    }

    @Test void aPipelineEventIsProcessedWithTheCommitsBesideIt() throws Exception {
        var p = newProject();
        var repo = attach(p);
        var sha = sha();
        var pipeline = new CicdEvent("pipeline", "pipeline:" + repo + ":1:success:t", externalId(repo), null, null,
                new CicdEvent.Pipeline("1", "CI", sha, "main", "success", "", null, null, OffsetDateTime.now()), null, null);
        events.store(connection, List.of(pipeline, commit(repo, sha, "고침", "junho.park@openpms.local")), "webhook");
        processor.run(Instant.now());
        assertThat(eventStatus("pipeline:" + repo + ":1:success:t")).isEqualTo("done");
        assertThat(eventStatus("commit:" + repo + ":" + sha)).isEqualTo("done");
        assertThat(db.fetchCount(com.openpms.db.Tables.CICD_PIPELINES, com.openpms.db.Tables.CICD_PIPELINES.REPOSITORY_ID.eq(repo))).isEqualTo(1);
    }

    @Test void commitLinksToWorkPackagesInTheProject() throws Exception {
        var p = newProject();
        long a = wp(p);
        var repo = attach(p);
        var sha = sha();
        assertThat(events.store(connection, List.of(commit(repo, sha, "고침 #" + a, "JUNHO.PARK@openpms.local")), "webhook")).isEqualTo(1);
        var journalsBefore = db.fetchCount(JOURNALS);
        assertThat(processor.run(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(db.fetchCount(JOURNALS)).isEqualTo(journalsBefore);
        var c = db.selectFrom(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo), CICD_COMMITS.SHA.eq(sha)).fetchOne();
        assertThat(c.getUserId()).isEqualTo(CicdFixture.JUNHO);
        var links = db.selectFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.COMMIT_ID.eq(c.getId())).fetch();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getSource()).isEqualTo("message");
    }

    @Test void refsOutsideTheProjectTreeAreIgnored() throws Exception {
        var root = newProject();
        var p = newSubProject(root);
        var sibling = newSubProject(root);
        var child = newSubProject(p);
        var other = newProject();
        var repo = attach(p);
        long q = wp(other), s = wp(sibling), r = wp(root);
        long infra = db.select(com.openpms.db.Tables.WORK_PACKAGES.NUMBER).from(com.openpms.db.Tables.WORK_PACKAGES)
                .where(com.openpms.db.Tables.WORK_PACKAGES.PROJECT_ID.eq(projectId("infra"))).limit(1).fetchOne().value1();
        events.store(connection, List.of(commit(repo, sha(), "남의 것 #" + q + " #" + infra + " #" + s + " #" + r, "x@example.com")), "poll");
        processor.run(Instant.now());
        assertThat(linksOf(repo)).isZero();

        long c = wp(child);
        events.store(connection, List.of(commit(repo, sha(), "하위 #" + c + " 남 #" + q + " #" + infra + " #" + s, "x@example.com")), "poll");
        processor.run(Instant.now());
        assertThat(linksOf(repo)).isEqualTo(1);
        assertThat(linker.projectTree(projectId(p))).containsExactlyInAnyOrder(projectId(p), projectId(child));
    }

    @Test void replayedAndPolledEventsApplyOnce() throws Exception {
        var p = newProject();
        long a = wp(p);
        var repo = attach(p);
        var e = commit(repo, sha(), "#" + a + " 다시", "x@example.com");
        events.store(connection, List.of(e), "webhook");
        events.store(connection, List.of(e), "webhook");
        events.store(connection, List.of(e), "poll");
        processor.run(Instant.now());
        assertThat(db.fetchCount(CICD_COMMITS, CICD_COMMITS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(linksOf(repo)).isEqualTo(1);

        var t1 = OffsetDateTime.now().minusHours(2);
        events.store(connection, List.of(mr(repo, 7, "opened", "정리 #" + a, "topic", t1)), "webhook");
        processor.run(Instant.now());
        assertThat(linksOf(repo)).isEqualTo(2);
        db.update(WORK_PACKAGE_CICD_LINKS).set(WORK_PACKAGE_CICD_LINKS.DISMISSED_AT, OffsetDateTime.now())
                .where(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID.in(db.select(CICD_MERGE_REQUESTS.ID).from(CICD_MERGE_REQUESTS)
                        .where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)))).execute();
        events.store(connection, List.of(mr(repo, 7, "merged", "정리 #" + a, "topic", t1.plusHours(1))), "poll");
        processor.run(Instant.now());
        var mrLinks = mrLinksOf(repo);
        assertThat(mrLinks).hasSize(1);
        assertThat(mrLinks.get(0).getDismissedAt()).isNotNull();
    }

    @Test void olderMergeRequestEventDoesNotRollBack() throws Exception {
        var p = newProject();
        var repo = attach(p);
        var t2 = OffsetDateTime.now().minusHours(1);
        events.store(connection, List.of(mr(repo, 3, "merged", "끝", "topic", t2)), "webhook");
        processor.run(Instant.now());
        events.store(connection, List.of(mr(repo, 3, "opened", "초안 제목", "topic", t2.minusHours(1))), "poll");
        processor.run(Instant.now());
        var row = db.selectFrom(CICD_MERGE_REQUESTS).where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo), CICD_MERGE_REQUESTS.IID.eq(3L)).fetchOne();
        assertThat(row.getState()).isEqualTo("merged");
        assertThat(row.getTitle()).isEqualTo("끝");
    }

    private UUID attachExternal(String project, String externalId) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, id).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, externalId).set(CICD_REPOSITORIES.FULL_PATH, "g/" + id)
                .set(CICD_REPOSITORIES.WEB_URL, "https://gitlab.example.com/g/" + id).set(CICD_REPOSITORIES.PROJECT_ID, projectId(project)).execute();
        return id;
    }

    private CicdEvent forExternal(CicdEvent e, String prefix, String externalId) {
        return new CicdEvent(e.kind(), prefix + e.commit().sha(), externalId, e.commit(), null);
    }

    @Test void unknownRepositoryEventIsDroppedAndComesBackAfterAttach() throws Exception {
        var p = newProject();
        long a = wp(p);
        var ext = "late-" + UUID.randomUUID();
        var stray = forExternal(commit(attach(p), sha(), "#" + a + " 모름", "x@example.com"), "commit:late:", ext);
        events.store(connection, List.of(stray), "webhook");
        processor.run(Instant.now());
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(connection), CICD_EVENTS.DELIVERY_KEY.eq(stray.deliveryKey()))).isZero();
        assertThat(db.fetchCount(CICD_COMMITS, CICD_COMMITS.SHA.eq(stray.commit().sha()))).isZero();
        // attached later: the same fact from the poll is accepted again
        var late = attachExternal(p, ext);
        assertThat(events.store(connection, List.of(stray), "poll")).isEqualTo(1);
        processor.run(Instant.now());
        assertThat(db.fetchCount(CICD_COMMITS, CICD_COMMITS.REPOSITORY_ID.eq(late))).isEqualTo(1);
        assertThat(linksOf(late)).isEqualTo(1);
    }

    @Test void repositoryAttachedBeforeProcessingIsFoundByExternalId() throws Exception {
        var p = newProject();
        long a = wp(p);
        var ext = "early-" + UUID.randomUUID();
        var e = forExternal(commit(attach(p), sha(), "#" + a, "x@example.com"), "commit:early:", ext);
        events.store(connection, List.of(e), "webhook");   // repository_id is null here
        var repo = attachExternal(p, ext);
        processor.run(Instant.now());
        assertThat(eventStatus(e.deliveryKey())).isEqualTo("done");
        assertThat(db.fetchCount(CICD_COMMITS, CICD_COMMITS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(linksOf(repo)).isEqualTo(1);
    }

    @Test void sameShaUnderTwoDeliveryKeysIsStoredOnce() throws Exception {
        var p = newProject();
        long a = wp(p);
        var repo = attach(p);
        var e = commit(repo, sha(), "#" + a, "x@example.com");
        events.store(connection, List.of(e), "webhook");
        events.store(connection, List.of(new CicdEvent(e.kind(), "other-key-" + e.commit().sha(), e.repoExternalId(), e.commit(), null)), "poll");
        processor.run(Instant.now());
        assertThat(db.fetchCount(CICD_COMMITS, CICD_COMMITS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(linksOf(repo)).isEqualTo(1);
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.REPOSITORY_ID.eq(repo), CICD_EVENTS.STATUS.eq("done"))).isEqualTo(2);
    }

    private CicdEvent mrBy(UUID repo, long iid, String title, String author, String email, OffsetDateTime updatedAt, String url) {
        return new CicdEvent("merge_request", "merge_request:" + repo + ":" + iid + ":" + updatedAt, externalId(repo), null,
                new CicdEvent.MergeRequest(iid, title, "", "open", "topic", "main", author, email, null, updatedAt, url));
    }

    @Test void theMergeRequestAuthorIsTheFirstOneSeen() throws Exception {
        var p = newProject();
        var repo = attach(p);
        var t0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
        events.store(connection, List.of(mrBy(repo, 31, "처음", "박준호", CicdFixture.JUNHO_EMAIL, t0, null)), "webhook");
        processor.run(Instant.now());
        // an update by someone else (a GitLab hook names whoever acted) and a poll without any address change nothing about the author
        events.store(connection, List.of(mrBy(repo, 31, "고침", "최민지", CicdFixture.MINJI_EMAIL, t0.plusMinutes(5), null)), "webhook");
        processor.run(Instant.now());
        events.store(connection, List.of(mrBy(repo, 31, "또 고침", "박준호", "", t0.plusMinutes(9), null)), "poll");
        processor.run(Instant.now());
        var row = db.selectFrom(CICD_MERGE_REQUESTS).where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)).fetchOne();
        assertThat(row.getTitle()).isEqualTo("또 고침");
        assertThat(row.getAuthorName()).isEqualTo("박준호");
        assertThat(row.getUserId()).isEqualTo(CicdFixture.JUNHO);

        // an MR first seen without an author takes the first one named later; "[REDACTED]" is no address
        events.store(connection, List.of(mrBy(repo, 32, "이름 없음", "", "", t0, null)), "webhook");
        processor.run(Instant.now());
        events.store(connection, List.of(mrBy(repo, 32, "이름 없음", "박준호", "[REDACTED]", t0.plusMinutes(1), null)), "poll");
        processor.run(Instant.now());
        var second = db.selectFrom(CICD_MERGE_REQUESTS).where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo), CICD_MERGE_REQUESTS.IID.eq(32L)).fetchOne();
        assertThat(second.getAuthorName()).isEqualTo("박준호");
        assertThat(second.getUserId()).isNull();
        assertThat(CicdProcessor.email("[REDACTED]")).isEmpty();
        assertThat(CicdProcessor.email(" a@b.c ")).isEqualTo("a@b.c");
    }

    @Test void addressesOffTheRepositoryHostAreNotStored() throws Exception {
        var p = newProject();
        var repo = attach(p);
        var repoUrl = "https://gitlab.example.com/g/" + repo;
        var t0 = OffsetDateTime.parse("2026-10-15T02:00:00Z");
        var evilSha = sha();
        events.store(connection, List.of(
                new CicdEvent("commit", "commit:" + repo + ":" + evilSha, externalId(repo),
                        new CicdEvent.Commit(evilSha, "c", "c", "박준호", CicdFixture.JUNHO_EMAIL, t0, "https://evil.example.com/phish"), null),
                mrBy(repo, 41, "자바스크립트", "박준호", "", t0, "javascript:alert(1)"),
                mrBy(repo, 42, "다른 포트", "박준호", "", t0, "https://gitlab.example.com:8443/g/x/-/merge_requests/42"),
                mrBy(repo, 43, "사용자 정보", "박준호", "", t0, "https://x@gitlab.example.com/g/x/-/merge_requests/43"),
                mrBy(repo, 44, "정상", "박준호", "", t0, "https://GITLAB.example.com/g/x/-/merge_requests/44")), "webhook");
        processor.run(Instant.now());
        assertThat(db.select(CICD_COMMITS.WEB_URL).from(CICD_COMMITS).where(CICD_COMMITS.SHA.eq(evilSha)).fetchOne().value1()).isEqualTo(repoUrl);
        var urls = db.select(CICD_MERGE_REQUESTS.IID, CICD_MERGE_REQUESTS.WEB_URL).from(CICD_MERGE_REQUESTS)
                .where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)).fetchMap(CICD_MERGE_REQUESTS.IID, CICD_MERGE_REQUESTS.WEB_URL);
        assertThat(urls.get(41L)).isEqualTo(repoUrl);
        assertThat(urls.get(42L)).isEqualTo(repoUrl);
        assertThat(urls.get(43L)).isEqualTo(repoUrl);
        assertThat(urls.get(44L)).isEqualTo("https://GITLAB.example.com/g/x/-/merge_requests/44");
        assertThat(CicdProcessor.safeUrl("https://gitlab.example.com:443/a", "https://gitlab.example.com/g")).isEqualTo("https://gitlab.example.com:443/a");
        assertThat(CicdProcessor.safeUrl("http://gitlab.example.com/a", "https://gitlab.example.com/g")).isEqualTo("https://gitlab.example.com/g");
    }

    @Test void lockedOrDeletedUsersAreNotMatched() throws Exception {
        var repo = attach(newProject());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        for (var status : List.of("locked", "deleted")) {
            var email = status + "-" + suffix + "@example.com";
            db.insertInto(com.openpms.db.Tables.USERS).set(com.openpms.db.Tables.USERS.EMAIL, email)
                    .set(com.openpms.db.Tables.USERS.NAME, status).set(com.openpms.db.Tables.USERS.STATUS, status).execute();
            var sha = sha();
            events.store(connection, List.of(commit(repo, sha, "x", email)), "poll");
            processor.run(Instant.now());
            var row = db.selectFrom(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo), CICD_COMMITS.SHA.eq(sha)).fetchOne();
            assertThat(row).isNotNull();
            assertThat(row.getUserId()).isNull();
        }
    }

    @Test void oneBadEventDoesNotStopTheRest() throws Exception {
        var p = newProject();
        long a = wp(p);
        var repo = attach(p);
        var good = commit(repo, sha(), "#" + a, "x@example.com");
        events.store(connection, List.of(good), "webhook");
        db.insertInto(CICD_EVENTS).set(CICD_EVENTS.CONNECTION_ID, connection).set(CICD_EVENTS.REPOSITORY_ID, repo)
                .set(CICD_EVENTS.KIND, "commit").set(CICD_EVENTS.DELIVERY_KEY, "broken-" + UUID.randomUUID())
                .set(CICD_EVENTS.PAYLOAD, JSONB.valueOf("{\"secret-token\":\"hush\"}")).set(CICD_EVENTS.VIA, "webhook")
                .set(CICD_EVENTS.RECEIVED_AT, OffsetDateTime.now().minusMinutes(5)).execute();
        processor.run(Instant.now());
        assertThat(eventStatus(good.deliveryKey())).isEqualTo("done");
        var bad = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.CONNECTION_ID.eq(connection), CICD_EVENTS.DELIVERY_KEY.like("broken-%")).fetchOne();
        assertThat(bad.getStatus()).isEqualTo("failed");
        assertThat(bad.getError()).isNotBlank().hasSizeLessThanOrEqualTo(500).doesNotContain("hush");
        assertThat(linksOf(repo)).isEqualTo(1);
    }

    @Test void branchReferenceFromMergeRequest() throws Exception {
        var p = newProject();
        long a = wp(p);
        var repo = attach(p);
        events.store(connection, List.of(mr(repo, 11, "opened", "로그인 화면", "feature/" + a + "-login", OffsetDateTime.now())), "webhook");
        processor.run(Instant.now());
        var links = mrLinksOf(repo);
        assertThat(links.stream().filter(l -> "branch".equals(l.getSource()))).hasSize(1);
    }
}

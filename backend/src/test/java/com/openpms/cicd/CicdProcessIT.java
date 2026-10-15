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

    @Test void unknownRepositoryIsIgnored() throws Exception {
        var p = newProject();
        var repo = attach(p);
        var e = commit(repo, sha(), "#1 모름", "x@example.com");
        var stray = new CicdEvent(e.kind(), "commit:stray:" + e.commit().sha(), "not-attached", e.commit(), null);
        events.store(connection, List.of(stray), "webhook");
        processor.run(Instant.now());
        assertThat(eventStatus(stray.deliveryKey())).isEqualTo("done");
        assertThat(db.fetchCount(CICD_COMMITS, CICD_COMMITS.SHA.eq(e.commit().sha()))).isZero();
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

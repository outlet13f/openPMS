package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.WORK_PACKAGE_CICD_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class DevTabIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final String NOT_FOUND = "작업을 찾을 수 없습니다.";
    static final String NO_MR = "이 프로젝트에 붙은 저장소의 MR/PR 주소가 아니거나 아직 받지 않은 MR/PR입니다.";
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEvents events;
    @Autowired CicdProcessor processor;
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

    private long wp(String project) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + project + "/work-packages"),
                "{\"subject\":\"개발 탭 " + UUID.randomUUID() + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn())
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

    private CicdEvent commit(UUID repo, String sha, String message) {
        return new CicdEvent("commit", "commit:" + repo + ":" + sha, externalId(repo),
                new CicdEvent.Commit(sha, message, message, "박준호", CicdFixture.JUNHO_EMAIL, OffsetDateTime.now(),
                        "https://gitlab.example.com/c/" + sha), null);
    }

    private CicdEvent mr(UUID repo, long iid, String title) {
        var now = OffsetDateTime.now();
        return new CicdEvent("merge_request", "merge_request:" + repo + ":" + iid + ":" + now, externalId(repo), null,
                new CicdEvent.MergeRequest(iid, title, "", "open", "topic", "main", "박준호", CicdFixture.JUNHO_EMAIL, null, now,
                        "https://gitlab.example.com/mr/" + repo + "/" + iid));
    }

    private String mrUrl(UUID repo, long iid) {
        return "https://gitlab.example.com/mr/" + repo + "/" + iid;
    }

    /** A project with the module on, one work package, a repository with a commit and an MR that both mention the work package. */
    private record Setup(String project, long number, UUID repo) {}

    private Setup setup() throws Exception {
        var p = f.newProject(admin, "work_packages", "cicd");
        long n = wp(p);
        var repo = attach(p);
        events.store(connection, List.of(commit(repo, sha(), "고침 #" + n), mr(repo, 5, "개발 탭 MR #" + n)), "webhook");
        processor.run(Instant.now());
        return new Setup(p, n, repo);
    }

    @Test void memberSeesAndCanUnlink() throws Exception {
        var s = setup();
        var junho = f.login(CicdFixture.JUNHO_EMAIL);
        var info = f.body(f.send(junho, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isOk()).andReturn());
        assertThat(info.get("canLink").asBoolean()).isTrue();
        assertThat(info.get("mergeRequests")).hasSize(1);
        assertThat(info.get("commits")).hasSize(1);
        var mr = info.get("mergeRequests").get(0);
        assertThat(mr.get("title").asString()).contains("개발 탭 MR");
        assertThat(mr.get("iid").asLong()).isEqualTo(5);
        assertThat(mr.get("state").asString()).isEqualTo("open");
        assertThat(mr.get("repository").asString()).isEqualTo(db.select(CICD_REPOSITORIES.FULL_PATH).from(CICD_REPOSITORIES)
                .where(CICD_REPOSITORIES.ID.eq(s.repo)).fetchOne().value1());
        assertThat(mr.at("/user/id").asString()).isEqualTo(CicdFixture.JUNHO.toString());
        assertThat(mr.get("source").asString()).isEqualTo("title");
        var commit = info.get("commits").get(0);
        assertThat(commit.get("shortSha").asString()).hasSize(7);
        assertThat(commit.get("source").asString()).isEqualTo("message");

        f.send(junho, delete("/api/v1/work-packages/" + s.number + "/cicd/links/" + commit.get("linkId").asString()), "")
                .andExpect(status().isNoContent());
        var after = f.body(f.send(junho, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isOk()).andReturn());
        assertThat(after.get("commits")).isEmpty();
        assertThat(after.get("mergeRequests")).hasSize(1);

        // the same commit event processed again does not bring the link back
        var sha = db.select(CICD_COMMITS.SHA).from(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(s.repo)).fetchOne().value1();
        events.store(connection, List.of(new CicdEvent("commit", "again-" + sha, externalId(s.repo),
                new CicdEvent.Commit(sha, "고침 #" + s.number, "고침 #" + s.number, "박준호", CicdFixture.JUNHO_EMAIL, OffsetDateTime.now(), "https://gitlab.example.com/c/" + sha), null)), "poll");
        processor.run(Instant.now());
        var again = f.body(f.send(junho, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isOk()).andReturn());
        assertThat(again.get("commits")).isEmpty();
    }

    @Test void observerSeesButCannotLink() throws Exception {
        var s = setup();
        var minji = f.login(CicdFixture.MINJI_EMAIL);
        var info = f.body(f.send(minji, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isOk()).andReturn());
        assertThat(info.get("canLink").asBoolean()).isFalse();
        assertThat(info.get("mergeRequests")).hasSize(1);
        var link = info.get("mergeRequests").get(0).get("linkId").asString();
        f.send(minji, delete("/api/v1/work-packages/" + s.number + "/cicd/links/" + link), "").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("개발 연결을 고칠 권한이 없습니다."));
        f.send(minji, post("/api/v1/work-packages/" + s.number + "/cicd/links"), "{\"url\":\"" + mrUrl(s.repo, 5) + "\"}")
                .andExpect(status().isForbidden());
        assertThat(f.body(f.send(minji, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andReturn()).get("mergeRequests")).hasSize(1);
    }

    @Test void nonMemberAndRequesterAreNotFound() throws Exception {
        var s = setup();
        f.send(admin, patch("/api/v1/projects/" + s.project), "{\"isPublic\":true}").andExpect(status().isOk());
        var doyoon = f.login("doyoon.han@openpms.local");
        // the work package itself is visible to a non-member of a public project, the development tab is not
        f.send(doyoon, get("/api/v1/work-packages/" + s.number), "").andExpect(status().isOk());
        f.send(doyoon, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value(NOT_FOUND));
        f.send(doyoon, post("/api/v1/work-packages/" + s.number + "/cicd/links"), "{\"url\":\"" + mrUrl(s.repo, 5) + "\"}")
                .andExpect(status().isNotFound());
        // private again: not even the work package
        f.send(admin, patch("/api/v1/projects/" + s.project), "{\"isPublic\":false}").andExpect(status().isOk());
        f.send(doyoon, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value(NOT_FOUND));
    }

    @Test void moduleOffIsNotFound() throws Exception {
        var s = setup();
        f.send(admin, patch("/api/v1/projects/" + s.project), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        var junho = f.login(CicdFixture.JUNHO_EMAIL);
        f.send(junho, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value(NOT_FOUND));
    }

    @Test void manualLinkByUrl() throws Exception {
        var p = f.newProject(admin, "work_packages", "cicd");
        long a = wp(p), b = wp(p);
        var repo = attach(p);
        events.store(connection, List.of(mr(repo, 9, "아무 관련 없는 MR")), "webhook");
        processor.run(Instant.now());
        var junho = f.login(CicdFixture.JUNHO_EMAIL);
        var url = mrUrl(repo, 9);
        // trailing slash and fragment are ignored
        f.send(junho, post("/api/v1/work-packages/" + a + "/cicd/links"), "{\"url\":\" " + url + "/#note_12 \"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("manual")).andExpect(jsonPath("$.title").value("아무 관련 없는 MR"));
        var link = db.selectFrom(WORK_PACKAGE_CICD_LINKS).where(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID.in(
                db.select(CICD_MERGE_REQUESTS.ID).from(CICD_MERGE_REQUESTS).where(CICD_MERGE_REQUESTS.REPOSITORY_ID.eq(repo)))).fetchOne();
        assertThat(link.getSource()).isEqualTo("manual");
        assertThat(link.getCreatedBy()).isEqualTo(CicdFixture.JUNHO);
        var info = f.body(f.send(junho, get("/api/v1/work-packages/" + a + "/cicd"), "").andExpect(status().isOk()).andReturn());
        assertThat(info.get("mergeRequests")).hasSize(1);

        // dismissed, then linked again by hand: revived, not duplicated
        f.send(junho, delete("/api/v1/work-packages/" + a + "/cicd/links/" + link.getId()), "").andExpect(status().isNoContent());
        f.send(junho, post("/api/v1/work-packages/" + a + "/cicd/links"), "{\"url\":\"" + url + "\"}").andExpect(status().isCreated());
        assertThat(db.fetchCount(WORK_PACKAGE_CICD_LINKS, WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID.eq(
                db.select(com.openpms.db.Tables.WORK_PACKAGES.ID).from(com.openpms.db.Tables.WORK_PACKAGES)
                        .where(com.openpms.db.Tables.WORK_PACKAGES.NUMBER.eq(a)).fetchOne().value1()))).isEqualTo(1);
        assertThat(f.body(f.send(junho, get("/api/v1/work-packages/" + a + "/cicd"), "").andReturn()).get("mergeRequests")).hasSize(1);

        // the repository address plus the MR number works too
        f.send(junho, post("/api/v1/work-packages/" + b + "/cicd/links"),
                "{\"url\":\"https://gitlab.example.com/g/" + repo + "/-/merge_requests/9\"}").andExpect(status().isCreated());
    }

    @Test void manualLinkOutsideTheTreeIsNotFound() throws Exception {
        var p = f.newProject(admin, "work_packages", "cicd");
        long a = wp(p);
        var other = f.newProject(admin, "work_packages", "cicd");
        var otherRepo = attach(other);
        events.store(connection, List.of(mr(otherRepo, 3, "다른 프로젝트의 비밀 MR 제목")), "webhook");
        processor.run(Instant.now());
        var junho = f.login(CicdFixture.JUNHO_EMAIL);
        var body = f.send(junho, post("/api/v1/work-packages/" + a + "/cicd/links"), "{\"url\":\"" + mrUrl(otherRepo, 3) + "\"}")
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(NO_MR).doesNotContain("비밀 MR 제목");
        var unknown = f.send(junho, post("/api/v1/work-packages/" + a + "/cicd/links"), "{\"url\":\"https://nowhere.example.com/x/1\"}")
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains(NO_MR);
    }

    @Test void linkIdOfAnotherWorkPackage() throws Exception {
        var s = setup();
        long other = wp(s.project);
        var junho = f.login(CicdFixture.JUNHO_EMAIL);
        var info = f.body(f.send(junho, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andReturn());
        var link = info.get("mergeRequests").get(0).get("linkId").asString();
        f.send(junho, delete("/api/v1/work-packages/" + other + "/cicd/links/" + link), "").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("연결을 찾을 수 없습니다."));
        f.send(junho, delete("/api/v1/work-packages/" + s.number + "/cicd/links/" + UUID.randomUUID()), "").andExpect(status().isNotFound());
        assertThat(f.body(f.send(junho, get("/api/v1/work-packages/" + s.number + "/cicd"), "").andReturn()).get("mergeRequests")).hasSize(1);
    }
}

package com.openpms.cicd;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Setup for the release ITs: a new private project with work packages and cicd (default environments 개발·스테이징·운영),
 * 한도윤 as QA, 박준호 as 멤버, 최민지 as 참관자, one attached repository, and SQL helpers for commits, deployments and
 * verifications (the processing that writes them is tested elsewhere, as in DevTabBuildsIT).
 */
public final class ReleaseFixture {
    public static final String HOST = "https://gitlab.example.com";
    public static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    public static final UUID ROLE_QA = UUID.fromString("55ecb595-b468-52b0-bfbd-52f40d6333a4");
    public static final String DOYOON_EMAIL = "doyoon.han@openpms.local";
    public static final String HANEUL_EMAIL = "haneul.lee@openpms.local";
    public static final UUID HANEUL = UUID.fromString("f0712dee-a7e4-5b9a-a9c5-7d3682ce763a");
    public static final String ALEX_EMAIL = "alex.kim@openpms.local";

    final CicdFixture f;
    final DSLContext db;
    MockHttpSession admin;
    UUID connection;
    String project;
    UUID projectId;
    UUID repo;
    int seq;

    public ReleaseFixture(MockMvc mvc, DSLContext db) throws Exception {
        this.f = new CicdFixture(mvc);
        this.db = db;
        admin = f.login(CicdFixture.ADMIN);
        connection = UUID.fromString(f.newConnection(admin, "gitlab", HOST, null)[0]);
        project = f.newProject(admin, "work_packages", "cicd");
        projectId = UUID.fromString(f.projectId(admin, project));
        f.addMember(admin, project, CicdFixture.DOYOON, ROLE_QA);
        repo = attach(projectId);
    }

    /** A new repository attached to the project. */
    UUID attach(UUID toProject) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_repositories (id, connection_id, external_id, full_path, web_url, project_id) values (?, ?, ?, ?, ?, ?)",
                id, connection, "x-" + id, "g/" + id, HOST + "/g/" + id, toProject);
        return id;
    }

    UUID env(String name) {
        return env(projectId, name);
    }

    UUID env(UUID ofProject, String name) {
        return db.fetchOne("select id from cicd_environments where project_id = ? and name = ?", ofProject, name).get(0, UUID.class);
    }

    /** A new work package of the project (or of the given project identifier). Returns its number. */
    long wp() throws Exception {
        return wp(project);
    }

    long wp(String inProject) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + inProject + "/work-packages"),
                "{\"subject\":\"릴리스 " + UUID.randomUUID() + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn())
                .get("number").asLong();
    }

    UUID wpId(long number) {
        return db.fetchOne("select id from work_packages where number = ?", number).get(0, UUID.class);
    }

    String sha() {
        return String.format("%040x", UUID.randomUUID().getMostSignificantBits() ^ (++seq));
    }

    /** A commit of the main repository, linked to the given work packages. */
    UUID commit(String sha, OffsetDateTime at, long... wps) {
        return commit(repo, sha, at, wps);
    }

    UUID commit(UUID repository, String sha, OffsetDateTime at, long... wps) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_commits (id, repository_id, sha, title, author_name, committed_at, web_url) values (?, ?, ?, '커밋', '박준호', ?::timestamptz, ?)",
                id, repository, sha, at, HOST + "/c/" + sha);
        for (var wp : wps)
            db.execute("insert into work_package_cicd_links (work_package_id, commit_id, source) values ((select id from work_packages where number = ?), ?, 'message')", wp, id);
        return id;
    }

    /** A commit linked to no work package. */
    UUID plainCommit(String sha, OffsetDateTime at) {
        return commit(repo, sha, at);
    }

    /** A merged merge request of the main repository with this merge commit, linked to the work package. */
    UUID mergedMr(String mergeSha, long wp) {
        return mr("merged", mergeSha, wp);
    }

    UUID mr(String state, String mergeSha, long wp) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_merge_requests (id, repository_id, iid, title, state, source_branch, target_branch, author_name, updated_at, web_url, merge_commit_sha) "
                + "values (?, ?, ?, 'MR', ?, ?, 'main', '박준호', now(), ?, ?)", id, repo, 100 + (++seq), state, "feature/" + wp, HOST + "/mr/" + seq, mergeSha);
        db.execute("insert into work_package_cicd_links (work_package_id, merge_request_id, source) values ((select id from work_packages where number = ?), ?, 'title')", wp, id);
        return id;
    }

    UUID deployment(String envName, String sha, String status, String inclusion, OffsetDateTime at) {
        return deployment(repo, envName, sha, status, inclusion, at, null);
    }

    UUID deployment(UUID repository, String envName, String sha, String status, String inclusion, OffsetDateTime at, OffsetDateTime cutoff) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_deployments (id, repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion, first_cutoff_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'gitlab', ?, ?::timestamptz)", id, repository, env(envName), envName, "d" + (++seq), sha, status,
                HOST + "/g/jobs/" + seq, at, inclusion, cutoff);
        return id;
    }

    /** The commit is now in the environment, brought in by the deployment (added_at is filled by the trigger). */
    void into(String envName, UUID commitId, UUID deploymentId) {
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) "
                + "select ?, repository_id, id, ? from cicd_commits where id = ?", env(envName), deploymentId, commitId);
    }

    /** A deployment of the commit to the environment that brings it in. Returns the deployment. */
    UUID deploy(String envName, UUID commitId, OffsetDateTime at) {
        var row = db.fetchOne("select repository_id, sha from cicd_commits where id = ?", commitId);
        var d = deployment(row.get(0, UUID.class), envName, row.get(1, String.class), "success", "compared", at, null);
        into(envName, commitId, d);
        return d;
    }

    OffsetDateTime addedAt(String envName, UUID commitId) {
        return db.fetchOne("select added_at from cicd_environment_commits where environment_id = ? and commit_id = ?", env(envName), commitId)
                .get(0, OffsetDateTime.class);
    }

    UUID verification(long wp, String envName, String result, String reason, OffsetDateTime verifiedAt, OffsetDateTime basisAt) {
        return verification(wp, env(envName), result, reason, verifiedAt, basisAt);
    }

    UUID verification(long wp, UUID environmentId, String result, String reason, OffsetDateTime verifiedAt, OffsetDateTime basisAt) {
        return verification(db, wp, environmentId, result, reason, verifiedAt, basisAt);
    }

    /** The same for ITs with their own setup (no fixture project): the environment by id, never looked up by name. */
    static UUID verification(DSLContext db, long wp, UUID environmentId, String result, String reason, OffsetDateTime verifiedAt, OffsetDateTime basisAt) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_verifications (id, work_package_id, environment_id, result, reason, verified_by, verified_at, basis_at) "
                + "values (?, (select id from work_packages where number = ?), ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
                id, wp, environmentId, result, reason, CicdFixture.DOYOON, verifiedAt, basisAt);
        return id;
    }

    void exclusion(long wp, String source, String target, OffsetDateTime addedBefore) {
        db.execute("insert into cicd_board_exclusions (work_package_id, environment_id, target_environment_id, excluded_by, added_before) "
                + "values ((select id from work_packages where number = ?), ?, ?, ?, ?::timestamptz)", wp, env(source), env(target), CicdFixture.DOYOON, addedBefore);
    }

    ResultActions boardRequest(String email, String query) throws Exception {
        return f.mvc.perform(get("/api/v1/projects/" + project + "/releases/board" + (query == null || query.isEmpty() ? "" : "?" + query))
                .session(f.login(email)));
    }

    JsonNode board(String email, String query) throws Exception {
        return f.body(boardRequest(email, query).andExpect(status().isOk()).andReturn());
    }

    /** The work package numbers of the cards, in board order. */
    static java.util.List<Long> numbers(JsonNode board) {
        return board.get("cards").valueStream().map(c -> c.get("number").asLong()).toList();
    }

    static JsonNode card(JsonNode board, long number) {
        for (var c : board.get("cards")) if (c.get("number").asLong() == number) return c;
        throw new AssertionError("no card #" + number + " in " + board.get("cards"));
    }
}

package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import tools.jackson.databind.JsonNode;

/** The development tab's build badges and the environment row, with rows written directly (the processing is tested elsewhere). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class DevTabBuildsIT {
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String HOST = "https://gitlab.example.com";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    CicdFixture f;
    MockHttpSession admin;
    String project;
    UUID projectId, repo;
    long wp;
    int seq;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        var connection = UUID.fromString(f.newConnection(admin, "gitlab", HOST, null)[0]);
        project = f.newProject(admin, "work_packages", "cicd");
        projectId = UUID.fromString(f.projectId(admin, project));
        repo = attach(connection, projectId);
        wp = newWp(project);
    }

    UUID attach(UUID connection, UUID project) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_repositories (id, connection_id, external_id, full_path, web_url, project_id) values (?, ?, ?, ?, ?, ?)",
                id, connection, "x-" + id, "g/" + id, HOST + "/g/" + id, project);
        return id;
    }

    long newWp(String projectIdentifier) throws Exception {
        return f.body(f.send(admin, post("/api/v1/projects/" + projectIdentifier + "/work-packages"),
                "{\"subject\":\"개발 탭 빌드 " + UUID.randomUUID() + "\",\"typeId\":\"" + TASK + "\"}").andExpect(status().isCreated()).andReturn())
                .get("number").asLong();
    }

    String sha() {
        return String.format("%040x", UUID.randomUUID().getMostSignificantBits() ^ (++seq));
    }

    /** A commit of the repository, linked to the work package. */
    UUID commit(String sha, OffsetDateTime at) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_commits (id, repository_id, sha, title, author_name, committed_at, web_url) values (?, ?, ?, '커밋', '박준호', ?::timestamptz, ?)",
                id, repo, sha, at, HOST + "/c/" + sha);
        db.execute("insert into work_package_cicd_links (work_package_id, commit_id, source) values ((select id from work_packages where number = ?), ?, 'message')", wp, id);
        return id;
    }

    UUID mr(String branch, String state, String mergeSha) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_merge_requests (id, repository_id, iid, title, state, source_branch, target_branch, author_name, updated_at, web_url, merge_commit_sha) "
                + "values (?, ?, ?, 'MR', ?, ?, 'main', '박준호', ?::timestamptz, ?, ?)", id, repo, 100 + (++seq), state, branch, T0, HOST + "/mr/" + seq, mergeSha);
        db.execute("insert into work_package_cicd_links (work_package_id, merge_request_id, source) values ((select id from work_packages where number = ?), ?, 'title')", wp, id);
        return id;
    }

    /** A commit that is not linked to the work package (it is in the repository only). */
    UUID plainCommit(String sha, OffsetDateTime at) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_commits (id, repository_id, sha, title, author_name, committed_at, web_url) values (?, ?, ?, '커밋', '박준호', ?::timestamptz, ?)",
                id, repo, sha, at, HOST + "/c/" + sha);
        return id;
    }

    void pipeline(String name, String sha, String ref, String status, OffsetDateTime at) {
        db.execute("insert into cicd_pipelines (repository_id, external_id, name, sha, ref, status, web_url, finished_at, source, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'gitlab', ?::timestamptz)", repo, "p" + (++seq), name, sha, ref, status, HOST + "/g/pipelines/" + seq,
                "running".equals(status) ? null : at, at);
    }

    UUID env(String name) {
        return db.fetchOne("select id from cicd_environments where project_id = ? and name = ?", projectId, name).get(0, UUID.class);
    }

    UUID deployment(String envName, String sha, String status, String inclusion, OffsetDateTime at) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_deployments (id, repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'gitlab', ?)", id, repo, env(envName), envName, "d" + (++seq), sha, status, HOST + "/g/jobs/" + seq, at, inclusion);
        return id;
    }

    void into(String envName, UUID commit, UUID deployment) {
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?)",
                env(envName), repo, commit, deployment);
    }

    JsonNode tab() throws Exception {
        return tab(CicdFixture.JUNHO_EMAIL);
    }

    JsonNode tab(String email) throws Exception {
        return f.body(f.mvc.perform(get("/api/v1/work-packages/" + wp + "/cicd").session(f.login(email))).andExpect(status().isOk()).andReturn());
    }

    JsonNode environment(JsonNode tab, String name) {
        for (var e : tab.get("environments")) if (name.equals(e.get("name").asString())) return e;
        throw new AssertionError("no environment " + name + " in " + tab.get("environments"));
    }

    @Test void buildBadgesOnCommitsAndMergeRequests() throws Exception {
        var b = sha();
        commit(b, T0);
        mr("feature/" + wp + "-x", "open", null);
        pipeline("build", b, "main", "success", T0.plusMinutes(5));
        pipeline("build", sha(), "feature/" + wp + "-x", "running", T0.plusMinutes(6));
        var tab = tab();
        assertThat(tab.at("/commits/0/build/status").asString()).isEqualTo("success");
        assertThat(tab.at("/commits/0/build/webUrl").asString()).startsWith(HOST);
        assertThat(tab.at("/commits/0/build/source").asString()).isEqualTo("gitlab");
        assertThat(tab.at("/commits/0/build/count").asInt()).isEqualTo(1);
        assertThat(tab.at("/mergeRequests/0/build/status").asString()).isEqualTo("running");
        assertThat(tab.at("/mergeRequests/0/build/webUrl").asString()).startsWith(HOST);
        assertThat(tab.at("/commits/0/detached").asBoolean()).isFalse();
    }

    @Test void aCommitWithoutBuildsHasNoBadge() throws Exception {
        commit(sha(), T0);
        assertThat(tab().at("/commits/0/build").isNull()).isTrue();
    }

    @Test void aFailedWorkflowIsNotHiddenByALaterSuccess() throws Exception {
        var b = sha();
        commit(b, T0);
        pipeline("build", b, "main", "failed", T0.plusMinutes(1));
        pipeline("lint", b, "main", "success", T0.plusMinutes(2));
        var build = tab().at("/commits/0/build");
        assertThat(build.get("status").asString()).isEqualTo("failed");
        assertThat(build.get("count").asInt()).isEqualTo(2);
        assertThat(build.get("webUrl").asString()).isEqualTo(HOST + "/g/pipelines/" + (seq - 1));
        pipeline("build", b, "main", "success", T0.plusMinutes(3));   // the same workflow runs again and passes
        build = tab().at("/commits/0/build");
        assertThat(build.get("status").asString()).isEqualTo("success");
        assertThat(build.get("count").asInt()).isEqualTo(2);
    }

    @Test void environmentRowShowsHowFarItWent() throws Exception {
        var b = sha();
        var c = commit(b, T0);
        var d = deployment("개발", b, "success", "compared", T0.plusHours(1));
        into("개발", c, d);
        var tab = tab();
        var names = tab.get("environments").valueStream().map(e -> e.get("name").asString()).toList();
        assertThat(names).containsExactly("개발", "스테이징", "운영");
        var dev = environment(tab, "개발");
        assertThat(dev.get("state").asString()).isEqualTo("in");
        assertThat(dev.get("included").asInt()).isEqualTo(1);
        assertThat(dev.get("total").asInt()).isEqualTo(1);
        assertThat(OffsetDateTime.parse(dev.get("deployedAt").asString()).toInstant()).isEqualTo(T0.plusHours(1).toInstant());
        assertThat(dev.get("webUrl").asString()).startsWith(HOST + "/g/jobs/");
        assertThat(dev.get("project").isNull()).isTrue();
        assertThat(environment(tab, "스테이징").get("state").asString()).isEqualTo("none");
        assertThat(environment(tab, "스테이징").get("deployedAt").isNull()).isTrue();
        assertThat(environment(tab, "운영").get("state").asString()).isEqualTo("none");
        assertThat(environment(tab, "운영").get("production").asBoolean()).isTrue();
    }

    @Test void partialWhenSomeCommitsAreIn() throws Exception {
        var shas = new String[] {sha(), sha(), sha()};
        var ids = new UUID[3];
        for (int i = 0; i < 3; i++) ids[i] = commit(shas[i], T0.plusMinutes(i));
        var d = deployment("운영", shas[1], "success", "partial", T0.plusHours(1));
        into("운영", ids[0], d);
        into("운영", ids[1], d);
        var prod = environment(tab(), "운영");
        assertThat(prod.get("state").asString()).isEqualTo("partial");
        assertThat(prod.get("included").asInt()).isEqualTo(2);
        assertThat(prod.get("total").asInt()).isEqualTo(3);
        assertThat(prod.get("partialCheck").asBoolean()).isTrue();
    }

    @Test void checkingAndFailedAreSaid() throws Exception {
        var b = sha();
        commit(b, T0);
        var d = deployment("스테이징", b, "success", "checking", T0.plusHours(1));
        assertThat(environment(tab(), "스테이징").get("state").asString()).isEqualTo("checking");
        db.execute("update cicd_deployments set inclusion = 'failed' where id = ?", d);
        db.execute("insert into cicd_environment_heads (repository_id, environment_id, sha, deployment_id, deployed_at) values (?, ?, ?, ?, ?::timestamptz)",
                repo, env("스테이징"), b, d, T0.plusHours(1));
        assertThat(environment(tab(), "스테이징").get("state").asString()).isEqualTo("failed");
    }

    @Test void aNewerCheckingDeploymentDoesNotHideThatEveryCommitIsIn() throws Exception {
        var shas = new String[] {sha(), sha()};
        var ids = new UUID[2];
        for (int i = 0; i < 2; i++) ids[i] = commit(shas[i], T0.plusMinutes(i));
        var d = deployment("운영", shas[1], "success", "compared", T0.plusHours(1));
        into("운영", ids[0], d);
        into("운영", ids[1], d);
        var prod = environment(tab(), "운영");
        assertThat(prod.get("state").asString()).isEqualTo("in");
        assertThat(prod.get("checking").asBoolean()).isFalse();
        deployment("운영", sha(), "success", "checking", T0.plusHours(2));
        prod = environment(tab(), "운영");
        assertThat(prod.get("state").asString()).isEqualTo("in");
        assertThat(prod.get("checking").asBoolean()).isTrue();
        assertThat(OffsetDateTime.parse(prod.get("deployedAt").asString()).toInstant()).isEqualTo(T0.plusHours(1).toInstant());
        // only part in: the newer deployment may bring the rest, so the row still says it is checking
        db.execute("delete from cicd_environment_commits where commit_id = ?", ids[1]);
        prod = environment(tab(), "운영");
        assertThat(prod.get("state").asString()).isEqualTo("checking");
        assertThat(prod.get("checking").asBoolean()).isTrue();
        assertThat(environment(tab(), "개발").get("checking").asBoolean()).isFalse();
    }

    @Test void commitsOlderThanTheFirstCheckedDeploymentAreBeforeIt() throws Exception {
        var b = sha();
        commit(b, T0);
        var d = deployment("개발", sha(), "success", "first", T0.plusDays(2));
        db.execute("update cicd_deployments set first_cutoff_at = ?::timestamptz where id = ?", T0.plusDays(1), d);
        assertThat(environment(tab(), "개발").get("state").asString()).isEqualTo("before_first");
        db.execute("update cicd_deployments set first_cutoff_at = ?::timestamptz where id = ?", T0.minusDays(1), d);
        assertThat(environment(tab(), "개발").get("state").asString()).isEqualTo("none");
    }

    @Test void commitShowsItsEnvironments() throws Exception {
        var b = sha();
        var c = commit(b, T0);
        into("개발", c, deployment("개발", b, "success", "compared", T0.plusHours(1)));
        into("스테이징", c, deployment("스테이징", b, "success", "compared", T0.plusHours(2)));
        var environments = tab().at("/commits/0/environments").valueStream().map(JsonNode::asString).toList();
        assertThat(environments).containsExactly("스테이징", "개발");
    }

    @Test void detachedRepositoryIsMarked() throws Exception {
        var b = sha();
        var c = commit(b, T0);
        mr("feature/x", "open", null);
        into("개발", c, deployment("개발", b, "success", "compared", T0.plusHours(1)));
        db.execute("update cicd_repositories set detached_at = now() where id = ?", repo);
        var tab = tab();
        assertThat(tab.at("/commits/0/detached").asBoolean()).isTrue();
        assertThat(tab.at("/mergeRequests/0/detached").asBoolean()).isTrue();
        assertThat(environment(tab, "개발").get("state").asString()).isEqualTo("in");
    }

    @Test void mergeRequestOnlyLinkStillHasEnvironments() throws Exception {
        var merge = sha();
        var c = plainCommit(merge, T0);
        mr("feature/" + wp + "-y", "merged", merge);   // stored in full (CicdProcessor.mergeCommit), joined by equality
        mr("feature/z", "open", merge);   // not merged: its merge sha does not count
        into("개발", c, deployment("개발", merge, "success", "compared", T0.plusHours(1)));
        var dev = environment(tab(), "개발");
        assertThat(dev.get("state").asString()).isEqualTo("in");
        assertThat(dev.get("total").asInt()).isEqualTo(1);
    }

    @Test void hiddenEnvironmentsDoNotShow() throws Exception {
        commit(sha(), T0);
        db.execute("update cicd_environments set hidden = true where project_id = ? and name = '스테이징'", projectId);
        var names = tab().get("environments").valueStream().map(e -> e.get("name").asString()).toList();
        assertThat(names).containsExactly("개발", "운영");
    }

    /** A private parent project (admin only) with the module on, and the project made its parent. Returns [id, name]. */
    Object[] parentOf(UUID child) throws Exception {
        var identifier = "pp-" + UUID.randomUUID().toString().substring(0, 8);
        var name = "비공개 부모 " + identifier;
        var id = f.body(f.send(admin, post("/api/v1/projects"), "{\"name\":\"" + name + "\",\"identifier\":\"" + identifier + "\"}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        f.send(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/projects/" + id),
                "{\"enabledModules\":[\"work_packages\",\"cicd\"]}").andExpect(status().isOk());
        db.execute("update projects set parent_id = ? where id = ?", UUID.fromString(id), child);
        return new Object[] {UUID.fromString(id), name};
    }

    UUID envIn(UUID project, String name) {
        return db.fetchOne("select id from cicd_environments where project_id = ? and name = ?", project, name).get(0, UUID.class);
    }

    @Test void commitsOutsideTheRepositoriesCurrentProjectTreeAreNotCounted() throws Exception {
        // the repository belongs to a sibling project now: a work package of this project is not in its tree
        var other = f.newProject(admin, "work_packages", "cicd");
        var otherId = UUID.fromString(f.projectId(admin, other));
        var b = sha();
        var c = commit(b, T0);
        into("개발", c, deployment("개발", b, "success", "compared", T0.plusHours(1)));
        db.execute("update cicd_repositories set project_id = ? where id = ?", otherId, repo);
        var tab = tab();
        assertThat(tab.get("environments")).isEmpty();
        assertThat(tab.at("/commits/0/environments")).isEmpty();
        assertThat(tab.get("commits")).hasSize(1);
    }

    @Test void environmentsOfOtherProjectsAreNotShownAndTwoProjectsNameThemselves() throws Exception {
        var unrelated = f.newProject(admin, "work_packages", "cicd");   // has environments, no linked commits: never shown
        assertThat(unrelated).isNotBlank();
        var parent = parentOf(projectId);
        var parentId = (UUID) parent[0];
        var parentName = (String) parent[1];
        var childName = db.fetchOne("select name from projects where id = ?", projectId).get(0, String.class);
        f.addMember(admin, parentId.toString(), CicdFixture.JUNHO, CicdFixture.ROLE_MEMBER);
        var connection = db.fetchOne("select connection_id from cicd_repositories where id = ?", repo).get(0, UUID.class);
        var parentRepo = attach(connection, parentId);
        var a = sha();
        db.execute("insert into cicd_commits (repository_id, sha, title, author_name, committed_at, web_url) values (?, ?, '부모', '박준호', ?::timestamptz, ?)", parentRepo, a, T0, HOST + "/c/a");
        db.execute("insert into work_package_cicd_links (work_package_id, commit_id, source) select (select id from work_packages where number = ?), id, 'message' from cicd_commits where repository_id = ? and sha = ?", wp, parentRepo, a);
        commit(sha(), T0);   // and one in the child's own repository
        var tab = tab();
        var pairs = tab.get("environments").valueStream().map(e -> e.get("project").asString() + "/" + e.get("name").asString()).toList();
        var first = parentName.compareTo(childName) < 0 ? parentName : childName;
        var second = first.equals(parentName) ? childName : parentName;
        assertThat(pairs).containsExactly(first + "/개발", first + "/스테이징", first + "/운영", second + "/개발", second + "/스테이징", second + "/운영");
    }

    @Test void aMemberOfTheChildOnlyIsNotShownThePrivateParent() throws Exception {
        var parent = parentOf(projectId);
        var parentId = (UUID) parent[0];
        var parentName = (String) parent[1];
        var connection = db.fetchOne("select connection_id from cicd_repositories where id = ?", repo).get(0, UUID.class);
        var parentRepo = attach(connection, parentId);
        var a = sha();
        db.execute("insert into cicd_commits (id, repository_id, sha, title, author_name, committed_at, web_url) values (gen_random_uuid(), ?, ?, '부모', '박준호', ?::timestamptz, ?)", parentRepo, a, T0, HOST + "/c/" + a);
        db.execute("insert into work_package_cicd_links (work_package_id, commit_id, source) select (select id from work_packages where number = ?), id, 'message' from cicd_commits where repository_id = ? and sha = ?", wp, parentRepo, a);
        var parentCommit = db.fetchOne("select id from cicd_commits where repository_id = ? and sha = ?", parentRepo, a).get(0, UUID.class);
        var dep = UUID.randomUUID();
        db.execute("insert into cicd_deployments (id, repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion) "
                + "values (?, ?, ?, '개발', 'pd', ?, 'success', ?, ?::timestamptz, 'gitlab', 'compared')", dep, parentRepo, envIn(parentId, "개발"), a, HOST + "/parent-job", T0);
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?)", envIn(parentId, "개발"), parentRepo, parentCommit, dep);
        var own = sha();
        var ownCommit = commit(own, T0.plusMinutes(1));
        into("개발", ownCommit, deployment("개발", own, "success", "compared", T0.plusHours(1)));

        var asChild = f.mvc.perform(get("/api/v1/work-packages/" + wp + "/cicd").session(f.login(CicdFixture.JUNHO_EMAIL))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var seen = f.json.readTree(asChild);
        assertThat(seen.get("environments")).hasSize(3);
        assertThat(seen.get("environments").valueStream().allMatch(e -> e.get("project").isNull())).isTrue();
        assertThat(asChild).doesNotContain(parentName).doesNotContain("parent-job");
        // the administrator sees both
        var all = tab(CicdFixture.ADMIN);
        assertThat(all.get("environments")).hasSize(6);
        assertThat(all.toString()).contains(parentName);
    }

    @Test void aMovedRepositoryDoesNotShowTheOldProjectsEnvironmentStates() throws Exception {
        var parent = parentOf(projectId);   // the repository moves up to the parent: still in the work package's tree
        var parentId = (UUID) parent[0];
        var b = sha();
        var c = commit(b, T0);
        into("개발", c, deployment("개발", b, "success", "compared", T0.plusHours(1)));
        deployment("스테이징", b, "success", "checking", T0.plusHours(2));
        db.execute("update cicd_repositories set project_id = ? where id = ?", parentId, repo);
        var tab = tab(CicdFixture.ADMIN);
        assertThat(tab.get("environments")).hasSize(3);
        for (var e : tab.get("environments")) {
            assertThat(e.get("state").asString()).as(e.get("name").asString()).isEqualTo("none");
            assertThat(e.get("included").asInt()).isZero();
            assertThat(e.get("deployedAt").isNull()).isTrue();
        }
        assertThat(tab.at("/commits/0/environments")).isEmpty();
    }

    @Test void aBranchNamedNullStillGetsABadge() throws Exception {
        mr("null", "open", null);
        pipeline("build", sha(), "null", "success", T0.plusMinutes(1));
        assertThat(tab().at("/mergeRequests/0/build/status").asString()).isEqualTo("success");
    }

    @Test void anObserverAlsoSeesTheRow() throws Exception {
        commit(sha(), T0);
        assertThat(tab(CicdFixture.MINJI_EMAIL).get("environments")).hasSize(3);
    }
}

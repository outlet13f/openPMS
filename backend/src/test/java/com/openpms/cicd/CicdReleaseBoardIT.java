package com.openpms.cicd;

import static com.openpms.cicd.ReleaseFixture.card;
import static com.openpms.cicd.ReleaseFixture.numbers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.core.Ctx;
import com.openpms.core.Current;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** The release verification board: the environment difference, the baseline time, columns, the version check and the banner. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdReleaseBoardIT {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String ADMIN = CicdFixture.ADMIN;
    static final String QA = ReleaseFixture.DOYOON_EMAIL;
    static final String DONE = "5aaf4021-de87-5376-81f4-50668e28b555";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired Current current;
    @Autowired CicdReleaseBoard boardService;
    ReleaseFixture r;

    @BeforeEach void setUp() throws Exception {
        r = new ReleaseFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(), db);
    }

    UUID version(UUID project, String name, String status, LocalDate end) {
        var id = UUID.randomUUID();
        db.execute("insert into versions (id, project_id, name, status, end_date) values (?, ?, ?, ?, ?::date)", id, project, name, status,
                end == null ? null : end.toString());
        return id;
    }

    void setVersion(long wp, UUID version) {
        db.execute("update work_packages set version_id = ? where number = ?", version, wp);
    }

    static List<String> missing(JsonNode board) {
        return board.get("missing").valueStream().map(m -> m.get("number").asLong() + ":" + m.get("group").asString()).toList();
    }

    @Test void differenceIsSourceMinusTarget() throws Exception {
        var repoB = r.attach(r.projectId);
        long wp1 = r.wp(), wp2 = r.wp(), wp3 = r.wp();
        var c1 = r.commit(r.sha(), T0, wp3);
        var c2 = r.commit(r.sha(), T0, wp1);
        var c3 = r.commit(repoB, r.sha(), T0, wp2);
        r.deploy("운영", c1, T0.plusHours(1));   // the target's first deployment is earlier than anything in the source
        r.deploy("스테이징", c1, T0.plusHours(2));
        var d2 = r.deploy("스테이징", c2, T0.plusHours(3));
        r.deploy("스테이징", c3, T0.plusHours(3));
        var board = r.board(QA, "");
        assertThat(board.get("sourceId").asString()).isEqualTo(r.env("스테이징").toString());
        assertThat(board.get("targetId").asString()).isEqualTo(r.env("운영").toString());
        assertThat(numbers(board)).containsExactly(wp1, wp2);
        assertThat(board.get("cards").valueStream().map(c -> c.get("column").asString()).toList()).containsOnly("waiting");
        var one = card(board, wp1);
        assertThat(OffsetDateTime.parse(one.get("cameInAt").asString()).toInstant()).isEqualTo(T0.plusHours(3).toInstant());
        assertThat(one.get("deploymentUrl").asString())
                .isEqualTo(db.fetchOne("select web_url from cicd_deployments where id = ?", d2).get(0, String.class));
        assertThat(one.get("last").isNull()).isTrue();
        assertThat(one.get("interloper").asBoolean()).isFalse();
        assertThat(one.at("/project/identifier").asString()).isEqualTo(r.project);
        assertThat(one.at("/status/name").asString()).isEqualTo("신규");
        assertThat(board.get("unlinkedCount").asInt()).isZero();
        assertThat(board.get("notices")).isEmpty();
        assertThat(board.get("environments").valueStream().map(e -> e.get("name").asString()).toList()).containsExactly("개발", "스테이징", "운영");
    }

    @Test void commitsBeforeTheTargetsFirstDeploymentAreLeftOut() throws Exception {
        var repoB = r.attach(r.projectId);
        long wp4 = r.wp(), wp5 = r.wp(), wp6 = r.wp();
        r.deployment(r.repo, "운영", r.sha(), "failed", "compared", T0, null);   // a failed deployment is not the baseline
        r.deployment(r.repo, "운영", r.sha(), "success", "unknown_commit", T0.plusHours(2), null);
        r.deploy("스테이징", r.commit(r.sha(), T0, wp4), T0.plusHours(1));
        r.deploy("스테이징", r.commit(r.sha(), T0, wp5), T0.plusHours(3));
        r.deploy("스테이징", r.commit(repoB, r.sha(), T0, wp6), T0.plusHours(1));   // repository B was never deployed to 운영
        assertThat(numbers(r.board(QA, ""))).containsExactly(wp5, wp6);
    }

    @Test void detachedAndMovedRepositoriesAreLeftOut() throws Exception {
        var repoB = r.attach(r.projectId);
        var repoC = r.attach(r.projectId);
        long a = r.wp(), b = r.wp(), c = r.wp();
        r.deploy("스테이징", r.commit(r.sha(), T0, a), T0.plusHours(1));
        r.deploy("스테이징", r.commit(repoB, r.sha(), T0, b), T0.plusHours(1));
        r.deploy("스테이징", r.commit(repoC, r.sha(), T0, c), T0.plusHours(1));
        assertThat(numbers(r.board(QA, ""))).containsExactly(a, b, c);
        db.execute("update cicd_repositories set detached_at = now() where id = ?", repoB);
        assertThat(numbers(r.board(QA, ""))).containsExactly(a, c);
        var other = UUID.fromString(r.f.projectId(r.admin, r.f.newProject(r.admin, "work_packages", "cicd")));
        db.execute("update cicd_repositories set project_id = ? where id = ?", other, repoC);
        assertThat(numbers(r.board(QA, ""))).containsExactly(a);
    }

    @Test void subProjectWorkPackagesShowOnlyToThoseWhoSeeThem() throws Exception {
        var child = "cd-" + UUID.randomUUID().toString().substring(0, 8);
        var childId = r.f.body(r.f.send(r.admin, post("/api/v1/projects"),
                "{\"name\":\"하위 " + child + "\",\"identifier\":\"" + child + "\",\"parentId\":\"" + r.projectId + "\"}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        r.f.send(r.admin, patch("/api/v1/projects/" + childId), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        long hidden = r.wp(child);
        r.deploy("스테이징", r.commit(r.sha(), T0, hidden), T0.plusHours(1));
        var plain = r.sha();
        r.deploy("스테이징", r.plainCommit(plain, T0), T0.plusHours(1));
        var admin = r.board(ADMIN, "");
        assertThat(numbers(admin)).containsExactly(hidden);
        assertThat(card(admin, hidden).at("/project/identifier").asString()).isEqualTo(child);
        assertThat(admin.get("unlinkedCount").asInt()).isEqualTo(1);
        var qa = r.board(QA, "");
        assertThat(qa.get("cards")).isEmpty();
        assertThat(qa.get("unlinkedCount").asInt()).isEqualTo(1);   // the commit of the hidden work package is not "unlinked"
        assertThat(qa.at("/unlinked/0/shortSha").asString()).isEqualTo(plain.substring(0, 7));
    }

    @Test void mergeCommitOfAMergedMrLinksTheWorkPackage() throws Exception {
        long wp8 = r.wp();
        var m = r.sha();
        var x = r.sha();
        r.mergedMr(m, wp8);
        var d = r.deployment(r.repo, "스테이징", m, "success", "compared", T0.plusHours(1), null);
        r.into("스테이징", r.plainCommit(m, T0), d);
        r.into("스테이징", r.plainCommit(x, T0.minusMinutes(5)), d);
        var board = r.board(QA, "");
        assertThat(numbers(board)).containsExactly(wp8);
        assertThat(board.get("unlinkedCount").asInt()).isEqualTo(1);
        var unlinked = board.at("/unlinked/0");
        assertThat(unlinked.get("shortSha").asString()).isEqualTo(x.substring(0, 7));
        assertThat(unlinked.get("webUrl").asString()).isEqualTo(ReleaseFixture.HOST + "/c/" + x);
        assertThat(unlinked.get("repository").asString()).startsWith("g/");
    }

    @Test void columnsFollowTheCurrentResult() throws Exception {
        long wp1 = r.wp(), wp2 = r.wp(), wp3 = r.wp();
        var c2 = r.commit(r.sha(), T0, wp1);
        var c3 = r.commit(r.sha(), T0, wp2);
        var c4 = r.commit(r.sha(), T0, wp3);
        r.deploy("스테이징", c2, T0.plusHours(3));
        r.deploy("스테이징", c3, T0.plusHours(3));
        r.deploy("스테이징", c4, T0.plusHours(3));
        r.verification(wp1, "스테이징", "passed", "", T0.plusHours(4), r.addedAt("스테이징", c2));
        r.verification(wp2, "스테이징", "failed", "로그인 실패", T0.plusHours(4), r.addedAt("스테이징", c3));
        r.verification(wp3, "스테이징", "failed", "다시 확인", T0.plusHours(4), r.addedAt("스테이징", c4));
        r.verification(wp3, "스테이징", "passed", "", T0.plusHours(5), r.addedAt("스테이징", c4));
        var board = r.board(QA, "");
        assertThat(numbers(board)).containsExactly(wp2, wp1, wp3);   // failed before passed, then by number
        assertThat(card(board, wp1).get("column").asString()).isEqualTo("passed");
        var failed = card(board, wp2);
        assertThat(failed.get("column").asString()).isEqualTo("failed");
        assertThat(failed.at("/last/result").asString()).isEqualTo("failed");
        assertThat(failed.at("/last/reason").asString()).isEqualTo("로그인 실패");
        assertThat(failed.at("/last/environment").asString()).isEqualTo("스테이징");
        assertThat(failed.at("/last/by/id").asString()).isEqualTo(CicdFixture.DOYOON.toString());
        assertThat(card(board, wp3).get("column").asString()).isEqualTo("passed");
        assertThat(db.fetchOne("select count(*) from cicd_verifications where work_package_id = ?", r.wpId(wp3)).get(0, Integer.class)).isEqualTo(2);
    }

    @Test void aNewCommitInTheSourceAsksForReverification() throws Exception {
        long wp1 = r.wp();
        var c2 = r.commit(r.sha(), T0, wp1);
        r.deploy("스테이징", c2, T0.plusHours(3));
        r.verification(wp1, "스테이징", "passed", "", T0.plusHours(4), r.addedAt("스테이징", c2));
        assertThat(card(r.board(QA, ""), wp1).get("column").asString()).isEqualTo("passed");
        r.deploy("스테이징", r.commit(r.sha(), T0.plusHours(4), wp1), T0.plusHours(5));
        var one = card(r.board(QA, ""), wp1);
        assertThat(one.get("column").asString()).isEqualTo("reverify");
        assertThat(one.at("/last/result").asString()).isEqualTo("passed");
        assertThat(OffsetDateTime.parse(one.get("cameInAt").asString()).toInstant()).isEqualTo(T0.plusHours(5).toInstant());
    }

    @Test void aCommitBackAfterARollbackAsksForReverification() throws Exception {
        long wp1 = r.wp();
        var c2 = r.commit(r.sha(), T0, wp1);
        r.deploy("스테이징", c2, T0.plusHours(3));
        r.verification(wp1, "스테이징", "passed", "", T0.plusHours(4), r.addedAt("스테이징", c2));
        db.execute("delete from cicd_environment_commits where environment_id = ? and commit_id = ?", r.env("스테이징"), c2);   // rolled back
        assertThat(r.board(QA, "").get("cards")).isEmpty();
        r.deploy("스테이징", c2, T0.plusHours(5));   // and deployed again
        assertThat(card(r.board(QA, ""), wp1).get("column").asString()).isEqualTo("reverify");
    }

    @Test void versionOverlay() throws Exception {
        var v = version(r.projectId, "1.0", "open", LocalDate.now().plusDays(30));
        long wp1 = r.wp(), wpB = r.wp(), wpC = r.wp(), wpD = r.wp(), wpE = r.wp(), wpF = r.wp(), wp2 = r.wp();
        for (var n : List.of(wp1, wpB, wpC, wpD, wpE, wpF)) setVersion(n, v);
        db.execute("update work_packages set status_id = ?::uuid where number = ?", DONE, wpD);
        r.mr("open", null, wpB);
        var cE = r.commit(r.sha(), T0, wpE);
        r.deploy("운영", cE, T0.plusHours(1));
        r.deploy("스테이징", cE, T0.plusHours(1));
        r.deploy("스테이징", r.commit(r.sha(), T0, wpF), T0.plusMinutes(30));   // before 운영's first deployment: counted as already out
        r.deploy("스테이징", r.commit(r.sha(), T0, wp1), T0.plusHours(3));
        r.deploy("스테이징", r.commit(r.sha(), T0, wp2), T0.plusHours(3));
        var board = r.board(QA, "version=" + v);
        assertThat(board.get("versionId").asString()).isEqualTo(v.toString());
        assertThat(numbers(board)).containsExactly(wp1, wp2);
        assertThat(card(board, wp1).get("interloper").asBoolean()).isFalse();
        assertThat(card(board, wp2).get("interloper").asBoolean()).isTrue();
        assertThat(board.get("interloperCount").asInt()).isEqualTo(1);
        assertThat(missing(board)).containsExactly(wpB + ":developing", wpC + ":no_code");
        var none = r.board(QA, "version=none");
        assertThat(none.get("versionId").isNull()).isTrue();
        assertThat(none.get("missing")).isEmpty();
        assertThat(none.get("interloperCount").asInt()).isZero();
        assertThat(none.get("cards").valueStream().map(c -> c.get("interloper").asBoolean()).toList()).containsOnly(false);
    }

    @Test void sharedVersionCountsOnlyVisibleWorkPackages() throws Exception {
        var v = version(r.projectId, "공유", "open", LocalDate.now().plusDays(30));
        var other = r.f.newProject(r.admin, "work_packages");
        long theirs = r.wp(other);
        setVersion(theirs, v);
        assertThat(missing(r.board(ADMIN, "version=" + v))).containsExactly(theirs + ":no_code");
        assertThat(r.board(QA, "version=" + v).get("missing")).isEmpty();
    }

    @Test void excludedWorkPackageIsHiddenUntilANewCommitComesIn() throws Exception {
        var v = version(r.projectId, "2.0", "open", LocalDate.now().plusDays(30));
        long wp1 = r.wp();
        setVersion(wp1, v);
        var c2 = r.commit(r.sha(), T0, wp1);
        r.deploy("스테이징", c2, T0.plusHours(3));
        r.exclusion(wp1, "스테이징", "운영", r.addedAt("스테이징", c2));
        var board = r.board(QA, "version=" + v);
        assertThat(board.get("cards")).isEmpty();
        assertThat(board.get("excluded").valueStream().map(e -> e.get("number").asLong()).toList()).containsExactly(wp1);
        assertThat(board.at("/excluded/0/by/id").asString()).isEqualTo(CicdFixture.DOYOON.toString());
        assertThat(board.get("missing")).isEmpty();
        assertThat(listed()).isEmpty();
        // another board of the same project is not touched
        r.deploy("개발", r.commit(r.sha(), T0, wp1), T0.plusHours(4));   // after 스테이징's first deployment (its baseline)
        var devToStaging = r.board(QA, "source=" + r.env("개발") + "&target=" + r.env("스테이징") + "&version=" + v);
        assertThat(numbers(devToStaging)).containsExactly(wp1);
        assertThat(devToStaging.get("excluded")).isEmpty();
        r.deploy("스테이징", r.commit(r.sha(), T0.plusHours(5), wp1), T0.plusHours(6));
        board = r.board(QA, "version=" + v);
        assertThat(numbers(board)).containsExactly(wp1);
        assertThat(card(board, wp1).get("column").asString()).isEqualTo("waiting");
        assertThat(board.get("excluded")).isEmpty();
        var listed = listed();
        assertThat(listed).containsOnlyKeys(r.wpId(wp1));
        assertThat(listed.get(r.wpId(wp1)).toInstant()).isEqualTo(T0.plusHours(6).toInstant());
    }

    /** The service view of the board (used by verifying and excluding) for the QA person: 스테이징 → 운영. */
    java.util.Map<UUID, OffsetDateTime> listed() {
        var ctx = new Ctx(CicdFixture.DOYOON, current::snapshot);
        var p = ctx.findProject(r.project);
        return boardService.listed(ctx, p, r.env("스테이징"), r.env("운영"));
    }

    @Test void defaults() throws Exception {
        var today = LocalDate.now();
        version(r.projectId, "지난", "open", today.minusDays(5));
        var soon = version(r.projectId, "곧", "open", today.plusDays(10));
        version(r.projectId, "나중", "open", today.plusDays(20));
        version(r.projectId, "닫힘", "closed", today.plusDays(2));
        version(r.projectId, "잠김", "locked", today.plusDays(3));
        version(r.projectId, "끝 없음", "open", null);
        var board = r.board(QA, "");
        assertThat(board.get("targetId").asString()).isEqualTo(r.env("운영").toString());
        assertThat(board.get("sourceId").asString()).isEqualTo(r.env("스테이징").toString());
        assertThat(board.get("versionId").asString()).isEqualTo(soon.toString());
        assertThat(board.get("versions")).hasSize(6);
        assertThat(board.get("canVerify").asBoolean()).isTrue();

        r.deploy("스테이징", r.commit(r.sha(), T0, r.wp()), T0.plusHours(1));
        db.execute("update cicd_environments set hidden = true where id = ?", r.env("운영"));
        board = r.board(QA, "");
        assertThat(board.get("sourceId").isNull()).isTrue();
        assertThat(board.get("targetId").isNull()).isTrue();
        assertThat(board.get("cards")).isEmpty();
        assertThat(board.get("notices")).isEmpty();
        assertThat(board.get("environments")).hasSize(3);

        db.execute("update cicd_environments set hidden = (name = '스테이징') where project_id = ?", r.projectId);
        board = r.board(QA, "");
        assertThat(board.get("sourceId").asString()).isEqualTo(r.env("개발").toString());
        assertThat(board.get("targetId").asString()).isEqualTo(r.env("운영").toString());
        // a hidden environment can still be chosen
        assertThat(r.board(QA, "source=" + r.env("스테이징")).get("sourceId").asString()).isEqualTo(r.env("스테이징").toString());
    }

    @Test void noticesFollowTheDevTabJudgment() throws Exception {
        r.deployment(r.repo, "스테이징", r.sha(), "success", "checking", T0.plusHours(2), null);
        var c = r.commit(r.sha(), T0, r.wp());
        var partial = r.deployment(r.repo, "스테이징", r.sha(), "success", "partial", T0.plusHours(1), null);
        r.into("스테이징", c, partial);
        var failed = r.deployment(r.repo, "운영", r.sha(), "success", "failed", T0.plusHours(1), null);
        db.execute("insert into cicd_environment_heads (environment_id, repository_id, sha, deployment_id, deployed_at) "
                + "select environment_id, repository_id, sha, id, deployed_at from cicd_deployments where id = ?", failed);
        r.deployment(r.repo, "운영", r.sha(), "success", "first", T0, T0.minusDays(3));
        // a detached repository's deployment says nothing
        var detached = r.attach(r.projectId);
        r.deployment(detached, "개발", r.sha(), "success", "checking", T0, null);
        db.execute("update cicd_repositories set detached_at = now() where id = ?", detached);
        var notices = r.board(QA, "").get("notices").valueStream()
                .map(n -> n.get("environment").asString() + ":" + n.get("kind").asString()).toList();
        assertThat(notices).containsExactly("스테이징:checking", "스테이징:partial", "운영:failed", "운영:first_cutoff");
        var dev = r.board(QA, "source=" + r.env("개발") + "&target=" + r.env("스테이징")).get("notices").valueStream()
                .map(n -> n.get("environment").asString() + ":" + n.get("kind").asString()).toList();
        assertThat(dev).containsExactly("스테이징:checking", "스테이징:partial");
        assertThat(r.board(QA, "").at("/notices/0/environmentId").asString()).isEqualTo(r.env("스테이징").toString());
    }

    @Test void whoSeesTheBoard() throws Exception {
        r.f.addMember(r.admin, r.project, ReleaseFixture.HANEUL, CicdFixture.ROLE_DEV_LEAD);
        assertThat(r.board(CicdFixture.MINJI_EMAIL, "").get("canVerify").asBoolean()).isFalse();
        assertThat(r.board(QA, "").get("canVerify").asBoolean()).isTrue();
        assertThat(r.board(ReleaseFixture.HANEUL_EMAIL, "").get("canVerify").asBoolean()).isTrue();
        assertThat(r.board(CicdFixture.JUNHO_EMAIL, "").get("canVerify").asBoolean()).isFalse();
        r.boardRequest(ReleaseFixture.ALEX_EMAIL, "").andExpect(status().isNotFound());
        r.f.send(r.admin, patch("/api/v1/projects/" + r.project), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        r.boardRequest(ADMIN, "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("프로젝트를 찾을 수 없습니다."));
    }

    @Test void unknownOrSameEnvironment() throws Exception {
        var other = UUID.fromString(r.f.projectId(r.admin, r.f.newProject(r.admin, "work_packages", "cicd")));
        r.boardRequest(ADMIN, "source=" + r.env(other, "스테이징")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("환경을 찾을 수 없습니다."));
        r.boardRequest(ADMIN, "target=" + r.env(other, "운영")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("환경을 찾을 수 없습니다."));
        r.boardRequest(ADMIN, "source=" + r.env("운영") + "&target=" + r.env("운영")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("비교 원본과 대상은 다른 환경이어야 합니다."));
        r.boardRequest(ADMIN, "version=" + version(other, "남의 것", "open", null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("버전을 찾을 수 없습니다."));
        r.boardRequest(ADMIN, "version=abc").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("버전을 찾을 수 없습니다."));
    }
}

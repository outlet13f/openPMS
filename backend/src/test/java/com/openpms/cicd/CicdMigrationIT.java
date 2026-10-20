package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdMigrationIT {
    @Autowired DSLContext db;

    List<String> perms(String id) {
        return List.of(db.fetchOne("select permissions from roles where id = ?::uuid", id).get(0, String[].class));
    }

    @Test void rolesGetTheCicdPermissions() {
        // 프로젝트 관리자 · 멤버 · 참관자
        assertThat(perms("0329397e-d933-5ac1-b27c-95889ba1a893")).contains("cicd.view", "cicd.link", "cicd.manage");
        assertThat(perms("6d4333fb-e975-5213-8ebc-552cca26e1d4")).contains("cicd.view", "cicd.link").doesNotContain("cicd.manage");
        assertThat(perms("f957f36a-72ca-5379-b8b9-c0e7dabe2d1e")).contains("cicd.view").doesNotContain("cicd.link", "cicd.manage");
        // 비멤버(내장 역할): 없음
        assertThat(perms("21f83874-5c75-520e-aa32-76a829a19e30")).noneMatch(p -> p.startsWith("cicd."));
        // no built-in role gets a cicd.* permission
        assertThat(db.fetchCount(db.selectFrom("roles").where("builtin is not null and exists (select 1 from unnest(permissions) p where p like 'cicd.%')")))
                .isZero();
    }

    @Test void devLeadIsMemberPlusManage() {
        var lead = perms("8e9c194d-fcdf-5c47-b081-ab2b2b61c89a");
        assertThat(lead).containsAll(perms("6d4333fb-e975-5213-8ebc-552cca26e1d4").stream().filter(p -> !p.equals("cicd.manage")).toList());
        assertThat(lead).contains("cicd.manage");
        assertThat(db.fetchOne("select name from roles where id = '8e9c194d-fcdf-5c47-b081-ab2b2b61c89a'").get(0, String.class)).isEqualTo("개발 리더");
        assertThat(db.fetchOne("select scope from roles where id = 'bae8d8f2-850d-5250-9c69-25b1631be394'").get(0, String.class)).isEqualTo("global");
        assertThat(perms("bae8d8f2-850d-5250-9c69-25b1631be394")).containsExactly("global.manage_cicd");
    }

    @Test void phaseTwoTablesAndKinds() {
        assertThat(db.fetchOne("select pg_get_constraintdef(oid) from pg_constraint where conname = 'cicd_connections_kind_check'").get(0, String.class))
                .contains("jenkins", "argocd");
        assertThat(db.fetchOne("select pg_get_constraintdef(oid) from pg_constraint where conname = 'cicd_events_kind_check'").get(0, String.class))
                .contains("pipeline", "deployment");
        for (var t : List.of("cicd_environments", "cicd_pipelines", "cicd_deployments", "cicd_deployment_commits",
                "cicd_environment_commits", "cicd_environment_heads", "cicd_first_deploy_checks"))
            assertThat(db.fetchOne("select to_regclass(?)::text", t).get(0, String.class)).isEqualTo(t);
        assertThat(db.fetchOne("select count(*) from information_schema.columns where table_name = 'cicd_repositories' and column_name = 'detached_at'").get(0, Integer.class)).isOne();
        assertThat(db.fetchOne("select count(*) from information_schema.columns where table_name = 'cicd_first_deploy_checks' and column_name = 'next_attempt_at'").get(0, Integer.class)).isOne();
    }

    @Test void projectsWithTheModuleHaveTheDefaultEnvironments() {
        // web-platform (V1010 turns the module on; V28 or V1012 adds the environments)
        var rows = db.fetch("select name, production, aliases from cicd_environments where project_id = '935daeb0-d1aa-57f4-88e1-0050b30413ac' order by position");
        assertThat(rows.getValues("name", String.class)).containsExactly("개발", "스테이징", "운영");
        assertThat(rows.get(2).get("production", Boolean.class)).isTrue();
        assertThat(List.of(rows.get(0).get("aliases", String[].class))).containsExactly("dev", "development");
    }

    @Test void moduleIsKnown() {
        assertThat(db.fetchOne("select pg_get_constraintdef(oid) from pg_constraint where conname = 'projects_known_modules'").get(0, String.class))
                .contains("cicd");
    }

    static final String QA = "55ecb595-b468-52b0-bfbd-52f40d6333a4";

    @Test void phaseThreeTablesAndReason() {
        for (var t : List.of("cicd_verifications", "cicd_board_exclusions", "cicd_releases", "cicd_release_items"))
            assertThat(db.fetchOne("select to_regclass(?)::text", t).get(0, String.class)).isEqualTo(t);
        assertThat(db.fetchOne("select pg_get_constraintdef(oid) from pg_constraint where conname = 'notifications_reason_check'").get(0, String.class))
                .contains("verification_failed", "date_alert_overdue");
        assertThat(db.fetchOne("select is_nullable from information_schema.columns where table_name = 'cicd_environment_commits' and column_name = 'added_at'")
                .get(0, String.class)).isEqualTo("NO");
    }

    @Test void rolesGetReleaseVerify() {
        assertThat(perms("0329397e-d933-5ac1-b27c-95889ba1a893")).contains("release.verify");      // 프로젝트 관리자 (cicd.manage)
        assertThat(perms("8e9c194d-fcdf-5c47-b081-ab2b2b61c89a")).contains("release.verify");      // 개발 리더
        assertThat(perms("6d4333fb-e975-5213-8ebc-552cca26e1d4")).doesNotContain("release.verify"); // 멤버
        assertThat(perms("f957f36a-72ca-5379-b8b9-c0e7dabe2d1e")).doesNotContain("release.verify"); // 참관자
        assertThat(perms(QA)).containsExactlyInAnyOrder("project.view", "wp.view", "wp.comment", "wp.edit_own_comments", "cicd.view", "release.verify");
        assertThat(db.fetchOne("select name from roles where id = ?::uuid", QA).get(0, String.class)).isEqualTo("QA");
        assertThat(db.fetchOne("select scope from roles where id = ?::uuid", QA).get(0, String.class)).isEqualTo("project");
    }

    @Test void devDataShowsTheReleaseBoard() {   // V1013
        var web = "935daeb0-d1aa-57f4-88e1-0050b30413ac";
        assertThat(db.fetchOne("select count(*) from members m join member_roles mr on mr.member_id = m.id join users u on u.id = m.user_id "
                + "where u.email = 'seoa.yoon@openpms.local' and m.project_id = ?::uuid and mr.role_id = '55ecb595-b468-52b0-bfbd-52f40d6333a4'", web)
                .get(0, Long.class)).isEqualTo(1L);
        assertThat(db.fetchOne("select count(*) from cicd_verifications v join cicd_environments e on e.id = v.environment_id "
                + "where e.project_id = ?::uuid and e.name = '스테이징'", web).get(0, Long.class)).isEqualTo(5L);
        assertThat(db.fetchOne("select count(*) from cicd_releases where project_id = ?::uuid", web).get(0, Long.class)).isEqualTo(2L);
        assertThat(db.fetchOne("select count(*) from cicd_release_items where warning = 'unverified'").get(0, Long.class)).isEqualTo(1L);
        assertThat(db.fetchOne("select h.deployment_id from cicd_environment_heads h join cicd_environments e on e.id = h.environment_id "
                + "where e.project_id = ?::uuid and e.name = '스테이징'", web).get(0, UUID.class))
                .isEqualTo(UUID.fromString("0e1f2a3b-0012-4e00-8000-000000006012"));
        // the failed verification notified both the assignee and the co-assignee (ruling F10)
        assertThat(db.fetchOne("select count(*) from notifications where reason = 'verification_failed' and journal_id = '0f4c5d6e-0004-4f00-8000-000000009004'")
                .get(0, Long.class)).isEqualTo(2L);
    }

    @Test void environmentCommitsWithoutAddedAtTakeTheDeploymentTime() {   // Review Focus 2
        var at = OffsetDateTime.parse("2026-10-15T03:00:00Z");
        var ids = scratchDeployment(at);   // [environment, repository, commit, deployment]
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?)",
                ids[0], ids[1], ids[2], ids[3]);
        assertThat(addedAt(ids[2])).isEqualTo(at.toInstant());
        // the upsert 2단계 uses (bringIn, first deployment) moves the deployment, never the time
        var later = scratchDeploymentOf(ids, at.plusDays(1));
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?) "
                + "on conflict (environment_id, commit_id) do update set deployment_id = excluded.deployment_id", ids[0], ids[1], ids[2], later);
        assertThat(addedAt(ids[2])).isEqualTo(at.toInstant());
        assertThat(db.fetchOne("select deployment_id from cicd_environment_commits where commit_id = ?", ids[2]).get(0, UUID.class)).isEqualTo(later);
    }

    @Autowired WebApplicationContext context;

    @Test void savingTheQaRoleInTheEditorKeepsReleaseVerify() throws Exception {   // Review Focus 1: AdminController.known drops keys it does not list
        var f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build());
        var admin = f.login(CicdFixture.ADMIN);
        var keys = "[\"project.view\",\"wp.view\",\"wp.comment\",\"wp.edit_own_comments\",\"cicd.view\",\"release.verify\"]";
        f.send(admin, MockMvcRequestBuilders.patch("/api/v1/admin/roles/" + QA), "{\"permissions\":" + keys + "}")
                .andExpect(MockMvcResultMatchers.status().isOk());
        assertThat(perms(QA)).contains("release.verify");
        // the permission list the role editor draws from has the key in the CI/CD group
        f.mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/permissions").session(admin))
                .andExpect(MockMvcResultMatchers.jsonPath("$[?(@.key == 'release.verify')].group").value("cicd"));
    }

    // scratch data for the added_at test: a project with the cicd module, an inactive connection, a repository, a commit,
    // an environment and a successful deployment. Removed after each test (the connection and the project cascade).
    private UUID scratchProject;
    private UUID scratchConnection;

    @AfterEach void removeScratch() {
        if (scratchConnection != null) db.execute("delete from cicd_connections where id = ?", scratchConnection);
        if (scratchProject != null) db.execute("delete from projects where id = ?", scratchProject);
        scratchConnection = null;
        scratchProject = null;
    }

    private java.time.Instant addedAt(UUID commitId) {
        return db.fetchOne("select added_at from cicd_environment_commits where commit_id = ?", commitId).get(0, OffsetDateTime.class).toInstant();
    }

    private UUID[] scratchDeployment(OffsetDateTime at) {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        scratchProject = db.fetchOne("insert into projects (identifier, name, enabled_modules) values (?, ?, array['work_packages','cicd']) returning id",
                "mig-" + suffix, "마이그레이션 " + suffix).get(0, UUID.class);
        scratchConnection = db.fetchOne("insert into cicd_connections (kind, name, base_url, hook_key, active) values ('gitlab', ?, 'https://git.example.test', ?, false) returning id",
                "마이그레이션 " + suffix, "mig-" + UUID.randomUUID()).get(0, UUID.class);
        var repo = db.fetchOne("insert into cicd_repositories (connection_id, external_id, full_path, web_url, project_id) values (?, ?, ?, ?, ?) returning id",
                scratchConnection, "mig-" + suffix, "group/mig-" + suffix, "https://git.example.test/group/mig-" + suffix, scratchProject).get(0, UUID.class);
        var commit = db.fetchOne("insert into cicd_commits (repository_id, sha, title, committed_at, web_url) values (?, ?, '마이그레이션 커밋', ?::timestamptz, '') returning id",
                repo, "a".repeat(32) + suffix, at.minusHours(1)).get(0, UUID.class);
        var env = db.fetchOne("insert into cicd_environments (project_id, name, position) values (?, '스테이징', 1) returning id", scratchProject).get(0, UUID.class);
        var ids = new UUID[] {env, repo, commit, null};
        ids[3] = scratchDeploymentOf(ids, at);
        return ids;
    }

    private UUID scratchDeploymentOf(UUID[] ids, OffsetDateTime at) {
        return db.fetchOne("insert into cicd_deployments (repository_id, environment_id, environment_name, external_id, status, deployed_at, source, inclusion) "
                + "values (?, ?, '스테이징', ?, 'success', ?::timestamptz, 'gitlab', 'compared') returning id",
                ids[1], ids[0], "mig-" + UUID.randomUUID(), at).get(0, UUID.class);
    }
}

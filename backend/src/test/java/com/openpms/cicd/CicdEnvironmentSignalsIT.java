package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The state signals of an environment (checking, a failed head, the first-deployment cutoff, partial), shared by the development tab and the release board. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdEnvironmentSignalsIT {
    static final String HOST = "https://gitlab.example.com";
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdEnvironmentSignals signals;
    UUID connection, projectId, repo;
    int seq;

    @BeforeEach void setUp() throws Exception {
        var f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        var admin = f.login(CicdFixture.ADMIN);
        connection = UUID.fromString(f.newConnection(admin, "gitlab", HOST, null)[0]);
        var project = f.newProject(admin, "work_packages", "cicd");
        projectId = UUID.fromString(f.projectId(admin, project));
        repo = attach();
    }

    UUID attach() {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_repositories (id, connection_id, external_id, full_path, web_url, project_id) values (?, ?, ?, ?, ?, ?)",
                id, connection, "x-" + id, "g/" + id, HOST + "/g/" + id, projectId);
        return id;
    }

    UUID env(String name) {
        return db.fetchOne("select id from cicd_environments where project_id = ? and name = ?", projectId, name).get(0, UUID.class);
    }

    UUID deployment(UUID repository, String envName, String status, String inclusion, OffsetDateTime at, OffsetDateTime cutoff) {
        var id = UUID.randomUUID();
        db.execute("insert into cicd_deployments (id, repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion, first_cutoff_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'gitlab', ?, ?::timestamptz)", id, repository, env(envName), envName, "d" + (++seq),
                String.format("%040x", UUID.randomUUID().getMostSignificantBits() ^ seq), status, HOST + "/g/jobs/" + seq, at, inclusion, cutoff);
        return id;
    }

    List<CicdEnvironmentSignals.Signal> rows(UUID... repositories) {
        return signals.rows(List.of(env("스테이징"), env("운영")), List.of(repositories));
    }

    @Test void checkingIsSeenPerEnvironment() {
        deployment(repo, "스테이징", "success", "checking", T0, null);
        var rows = rows(repo);
        assertThat(CicdEnvironmentSignals.summarize(rows, env("스테이징"), Set.of(repo)).checking()).isTrue();
        assertThat(CicdEnvironmentSignals.summarize(rows, env("운영"), Set.of(repo)).checking()).isFalse();
    }

    @Test void onlyAFailedHeadCounts() {
        deployment(repo, "운영", "success", "failed", T0, null);
        var newer = deployment(repo, "운영", "success", "failed", T0.plusMinutes(5), null);
        assertThat(CicdEnvironmentSignals.summarize(rows(repo), env("운영"), Set.of(repo)).failed()).isFalse();
        db.execute("insert into cicd_environment_heads (environment_id, repository_id, sha, deployment_id, deployed_at) "
                + "select environment_id, repository_id, sha, id, deployed_at from cicd_deployments where id = ?", newer);
        assertThat(CicdEnvironmentSignals.summarize(rows(repo), env("운영"), Set.of(repo)).failed()).isTrue();
    }

    @Test void firstCutoffPerRepository() {
        var repoB = attach();
        var t1 = T0.minusDays(2);
        var t2 = T0.minusDays(1);
        deployment(repo, "스테이징", "success", "first", T0, t1);
        deployment(repo, "스테이징", "success", "first", T0.plusMinutes(1), t1.plusHours(5));
        deployment(repoB, "스테이징", "success", "first", T0, t2);
        deployment(repoB, "운영", "success", "first", T0, null);
        var s = CicdEnvironmentSignals.summarize(rows(repo, repoB), env("스테이징"), Set.of(repo, repoB));
        assertThat(s.firstCutoffs()).hasSize(2);
        assertThat(s.firstCutoffs().get(repo).toInstant()).isEqualTo(t1.toInstant());
        assertThat(s.firstCutoffs().get(repoB).toInstant()).isEqualTo(t2.toInstant());
        assertThat(CicdEnvironmentSignals.summarize(rows(repo, repoB), env("운영"), Set.of(repo, repoB)).firstCutoffs()).isEmpty();
    }

    @Test void otherRepositoriesAreNotCounted() {
        var repoB = attach();
        deployment(repo, "스테이징", "success", "checking", T0, null);
        var rows = rows(repo, repoB);
        assertThat(CicdEnvironmentSignals.summarize(rows, env("스테이징"), Set.of(repoB)).checking()).isFalse();
        assertThat(CicdEnvironmentSignals.summarize(rows, env("스테이징"), Set.of(repo)).checking()).isTrue();
    }

    @Test void partialEnvironments() {
        var commit = UUID.randomUUID();
        db.execute("insert into cicd_commits (id, repository_id, sha, title, author_name, committed_at, web_url) values (?, ?, ?, '커밋', '박준호', ?::timestamptz, ?)",
                commit, repo, String.format("%040x", ++seq), T0, HOST + "/c/" + seq);
        var d = deployment(repo, "스테이징", "success", "partial", T0, null);
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?)", env("스테이징"), repo, commit, d);
        // not partial: a fully compared deployment's commit, and another repository's commit from a partial deployment
        var compared = deployment(repo, "운영", "success", "compared", T0, null);
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?)", env("운영"), repo, commit, compared);
        var repoB = attach();
        var other = UUID.randomUUID();
        db.execute("insert into cicd_commits (id, repository_id, sha, title, author_name, committed_at, web_url) values (?, ?, ?, '커밋', '박준호', ?::timestamptz, ?)",
                other, repoB, String.format("%040x", ++seq), T0, HOST + "/c/" + seq);
        var partialB = deployment(repoB, "개발", "success", "partial", T0, null);
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) values (?, ?, ?, ?)", env("개발"), repoB, other, partialB);
        assertThat(signals.partial(List.of(env("개발"), env("스테이징"), env("운영")), List.of(repo))).containsExactly(env("스테이징"));
        assertThat(signals.partial(List.of(env("개발"), env("스테이징"), env("운영")), List.of(repoB))).containsExactly(env("개발"));
    }
}

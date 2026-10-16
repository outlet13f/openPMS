package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitLabCompareTest {
    FakeGitServer git;
    GitLabAdapter gitlab;
    CommitGraph graph;
    CicdAdapter.Remote remote;

    @BeforeEach void setUp() {
        git = new FakeGitServer();
        gitlab = new GitLabAdapter(new CicdHttp(), JsonMapper.builder().build());
        remote = new CicdAdapter.Remote(git.baseUrl(), "glpat-compare");
        graph = new CommitGraph().commit("a000000", "시작").commit("b000000", "로그인 #12", "a000000")
                .commit("c000000", "결제", "b000000").commit("d000000", "핫픽스", "a000000");
        git.respondWith("/api/v4/projects/77/repository/compare", r -> graph.gitlabCompare(r.query()));
        git.respondWith("/api/v4/projects/77/repository/merge_base", r -> graph.gitlabMergeBase(r.query()));
    }

    @AfterEach void tearDown() { git.close(); }

    @Test void compareListsWhatHeadAddsNewestFirst() throws Exception {
        var c = gitlab.compare(remote, "77", "a000000", "c000000", 1000);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha).containsExactly("c000000", "b000000");
        assertThat(c.truncated()).isFalse();
        assertThat(gitlab.compare(remote, "77", "c000000", "a000000", 1000).commits()).isEmpty();   // a rollback adds nothing
        assertThat(git.requests().get(0).query()).contains("from=a000000", "to=c000000", "straight=false");
        assertThat(git.requests().get(0).headers().get("private-token")).isEqualTo("glpat-compare");
    }

    @Test void compareReadsTheCommitFields() throws Exception {
        git.respond("/api/v4/projects/55/repository/compare", 200, "cicd/gitlab-compare.json");
        var c = gitlab.compare(remote, "55", "a000000", "c000000", 1000);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha)
                .containsExactly("c000000000000000000000000000000000000003", "b000000000000000000000000000000000000002");
        var first = c.commits().get(1);
        assertThat(first.title()).isEqualTo("로그인 #12");
        assertThat(first.message()).startsWith("로그인 #12\n");
        assertThat(first.authorName()).isEqualTo("박준호");
        assertThat(first.authorEmail()).isEqualTo("junho.park@openpms.local");
        assertThat(first.committedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:01:00Z"));
        assertThat(first.webUrl()).endsWith("/-/commit/b000000000000000000000000000000000000002");
        // no committed_date: created_at stands in
        assertThat(c.commits().get(0).committedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:02:00Z"));
    }

    @Test void limitCutsAndSaysSo() throws Exception {
        var c = gitlab.compare(remote, "77", "a000000", "c000000", 1);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha).containsExactly("c000000");
        assertThat(c.truncated()).isTrue();
    }

    @Test void aFailingCompareIsAnIoException() {
        assertThatThrownBy(() -> gitlab.compare(remote, "77", "a000000", "zzzzzzz", 1000))
                .isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 404");
    }

    @Test void tooLargeCompareFallsBackToTheCommitRange() throws Exception {
        git.respond("/api/v4/projects/88/repository/compare", 200, "{\"commits\":[],\"diffs\":[{\"diff\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}]}");
        git.respond("/api/v4/projects/88/repository/commits", 200,
                "[{\"id\":\"c000000\",\"title\":\"결제\",\"message\":\"결제\",\"author_name\":\"박준호\",\"author_email\":\"junho.park@openpms.local\","
                        + "\"committed_date\":\"2026-10-15T10:02:00Z\",\"web_url\":\"" + git.baseUrl() + "/g/r/-/commit/c000000\"}]");
        var c = gitlab.compare(remote, "88", "b000000", "c000000", 1000);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha).containsExactly("c000000");
        assertThat(c.truncated()).isFalse();
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/repository/commits") && r.query().contains("ref_name=b000000..c000000"));
    }

    @Test void theFallbackReadsPagesUpToTheCapAndSaysSoWhenStillFull() throws Exception {
        git.respond("/api/v4/projects/88/repository/compare", 200, "{\"commits\":[],\"diffs\":[{\"diff\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}]}");
        var page = new StringBuilder("[");
        for (int i = 0; i < CicdAdapter.PAGE_SIZE; i++) {
            if (i > 0) page.append(',');
            page.append("{\"id\":\"").append(graph.sha(i + 1)).append("\",\"title\":\"t\",\"message\":\"t\",\"committed_date\":\"2026-10-15T10:00:00Z\"}");
        }
        page.append(']');
        git.respond("/api/v4/projects/88/repository/commits", 200, page.toString());
        var c = gitlab.compare(remote, "88", "b000000", "c000000", 100_000);
        assertThat(c.truncated()).isTrue();
        assertThat(git.requests().stream().filter(r -> r.path().endsWith("/repository/commits")).count()).isEqualTo(CicdAdapter.MAX_PAGES);
    }

    @Test void aLargeButAllowedCompareStillWorks() throws Exception {
        // 3 MiB is over the plain 2 MiB cap and under the 8 MiB compare cap
        git.respond("/api/v4/projects/89/repository/compare", 200,
                "{\"commits\":[{\"id\":\"c000000\",\"title\":\"결제\",\"message\":\"결제\",\"committed_date\":\"2026-10-15T10:02:00Z\"}],"
                        + "\"diffs\":[{\"diff\":\"" + "x".repeat(3 * 1024 * 1024) + "\"}]}");
        var c = gitlab.compare(remote, "89", "b000000", "c000000", 1000);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha).containsExactly("c000000");
        assertThat(git.requests()).noneMatch(r -> r.path().endsWith("/repository/commits"));
    }

    @Test void containsUsesTheMergeBase() throws Exception {
        assertThat(gitlab.contains(remote, "77", "c000000", "b000000")).isTrue();
        assertThat(gitlab.contains(remote, "77", "c000000", "c000000")).isTrue();
        assertThat(gitlab.contains(remote, "77", "c000000", "d000000")).isFalse();   // the hotfix branch is not in main's deploy
        assertThat(git.requests()).anyMatch(r -> r.query().contains("refs%5B%5D=b000000") || r.query().contains("refs[]=b000000"));
    }

    @Test void containsAcceptsAShortCommitNumber() throws Exception {
        git.respond("/api/v4/projects/90/repository/merge_base", 200, "{\"id\":\"bcbb5ec396a2c0f828686f14fac9b80b780504f2\"}");
        assertThat(gitlab.contains(remote, "90", "ffffffffffffffffffffffffffffffffffffffff", "bcbb5ec3")).isTrue();
        assertThat(gitlab.contains(remote, "90", "ffffffffffffffffffffffffffffffffffffffff", "BCBB5EC3")).isTrue();
        assertThat(gitlab.contains(remote, "90", "ffffffffffffffffffffffffffffffffffffffff", "bcbb5ec4")).isFalse();
    }

    @Test void anUnknownCommitIsNotContained() throws Exception {
        // a commit deleted since (a force push): one stale commit must not fail the whole first deployment
        assertThat(gitlab.contains(remote, "77", "c000000", "9999999")).isFalse();
    }

    @Test void otherFailuresOfContainsAreIoExceptions() {
        git.respond("/api/v4/projects/91/repository/merge_base", 500, "{}");
        assertThatThrownBy(() -> gitlab.contains(remote, "91", "c000000", "b000000"))
                .isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 500");
        git.respond("/api/v4/projects/92/repository/merge_base", 401, "{}");
        assertThatThrownBy(() -> gitlab.contains(remote, "92", "c000000", "b000000"))
                .isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 401");
    }

    @Test void pollAlsoReadsPipelinesAndDeployments() throws Exception {
        git.respond("/api/v4/projects/77/repository/commits", 200, "[]");
        git.respond("/api/v4/projects/77/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/77/pipelines", 200, "cicd/gitlab-pipelines.json");
        git.respond("/api/v4/projects/77/deployments", 200, "cicd/gitlab-deployments.json");
        var polled = gitlab.poll(remote, "77", OffsetDateTime.parse("2026-10-14T00:00:00Z"));
        assertThat(polled.events()).extracting(CicdEvent::kind).containsExactly("pipeline", "pipeline", "deployment", "deployment");
        assertThat(polled.truncated()).isFalse();
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/pipelines") && r.query().contains("updated_after=") && r.query().contains("order_by=updated_at")
                && r.query().contains("sort=desc") && r.query().contains("per_page=100") && r.query().contains("page=1"));
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/deployments") && r.query().contains("updated_after=") && r.query().contains("order_by=updated_at"));
        List<String> keys = polled.events().stream().map(CicdEvent::deliveryKey).toList();
        assertThat(keys).allMatch(k -> k.startsWith("commit:") || k.startsWith("mr:") || k.startsWith("pipeline:77:") || k.startsWith("deployment:77:"));
        assertThat(keys).contains("pipeline:77:47:success:2026-10-15T10:00:30Z", "deployment:77:41:success:2026-10-15T10:12:00Z");
        var done = polled.events().get(0).pipeline();
        assertThat(done.externalId()).isEqualTo("47");
        assertThat(done.name()).isEqualTo("CI");
        assertThat(done.status()).isEqualTo("success");
        assertThat(done.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:00:30.500Z"));
        assertThat(done.finishedAt()).isEqualTo(done.updatedAt());   // a finished pipeline's last update is its end
        assertThat(done.webUrl()).endsWith("/-/pipelines/47");
        var running = polled.events().get(1).pipeline();
        assertThat(running.status()).isEqualTo("running");
        assertThat(running.finishedAt()).isNull();
        var deployed = polled.events().get(2).deployment();
        assertThat(deployed.externalId()).isEqualTo("41");
        assertThat(deployed.sha()).isEqualTo("a91957a858320c0e17f3a0eca7cfacbff50e4b3c");
        assertThat(deployed.environment()).isEqualTo("staging");
        assertThat(deployed.webUrl()).endsWith("/-/jobs/796");
        assertThat(deployed.deployedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:12:00.250Z"));
        assertThat(polled.events().get(3).deployment().status()).isEqualTo("failed");
    }

    @Test void pipelinesAndDeploymentsThatAnswer403Or404CountAsEmpty() throws Exception {
        git.respond("/api/v4/projects/77/repository/commits", 200, "cicd/gitlab-commits.json");
        git.respond("/api/v4/projects/77/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/77/pipelines", 403, "{}");
        git.respond("/api/v4/projects/77/deployments", 404, "{}");
        var polled = gitlab.poll(remote, "77", null);
        assertThat(polled.events()).isNotEmpty().allMatch(e -> e.kind().equals("commit"));
        git.respond("/api/v4/projects/77/pipelines", 500, "{}");
        assertThatThrownBy(() -> gitlab.poll(remote, "77", null)).isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 500");
    }

    @Test void pollWithoutASinceAsksForNoUpdatedAfter() throws Exception {
        git.respond("/api/v4/projects/77/repository/commits", 200, "[]");
        git.respond("/api/v4/projects/77/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/77/pipelines", 200, "[]");
        git.respond("/api/v4/projects/77/deployments", 200, "[]");
        gitlab.poll(remote, "77", null);
        assertThat(git.requests()).filteredOn(r -> r.path().endsWith("/pipelines") || r.path().endsWith("/deployments"))
                .hasSize(2).allMatch(r -> !r.query().contains("updated_after="));
    }

    @Test void pollSaysTruncatedWhenAListIsStillFullAtTheCap() throws Exception {
        var full = new StringBuilder("[");
        for (int i = 0; i < CicdAdapter.PAGE_SIZE; i++) {
            if (i > 0) full.append(',');
            full.append("{\"id\":").append(1000 + i).append(",\"sha\":\"").append(graph.sha(i + 1))
                    .append("\",\"ref\":\"main\",\"status\":\"success\",\"updated_at\":\"2026-10-15T10:00:00Z\"}");
        }
        full.append(']');
        git.respond("/api/v4/projects/77/repository/commits", 200, "[]");
        git.respond("/api/v4/projects/77/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/77/pipelines", 200, full.toString());
        git.respond("/api/v4/projects/77/deployments", 200, "[]");
        var polled = gitlab.poll(remote, "77", OffsetDateTime.parse("2026-10-14T00:00:00Z"));
        assertThat(polled.truncated()).isTrue();
        assertThat(git.requests().stream().filter(r -> r.path().endsWith("/pipelines")).count()).isEqualTo(CicdAdapter.MAX_PAGES);
    }
}

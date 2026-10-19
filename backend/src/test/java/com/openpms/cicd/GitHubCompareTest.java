package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitHubCompareTest {
    FakeGitServer git;
    GitHubAdapter github;
    CommitGraph graph;
    CicdAdapter.Remote remote;

    @BeforeEach void setUp() {
        git = new FakeGitServer();
        github = new GitHubAdapter(new CicdHttp(), JsonMapper.builder().build());
        remote = new CicdAdapter.Remote(git.baseUrl(), "ghp-compare");
        graph = new CommitGraph().commit("a000000", "시작").commit("b000000", "로그인 #12", "a000000")
                .commit("c000000", "결제", "b000000").commit("d000000", "핫픽스", "a000000");
        git.respondWith("/api/v3/repositories/9001/compare/", r -> graph.githubCompare(r.path(), r.query()));
    }

    @AfterEach void tearDown() { git.close(); }

    private List<Integer> comparePages() {
        return git.requests().stream().filter(r -> r.path().contains("/compare/"))
                .map(r -> Integer.parseInt(java.util.Arrays.stream(r.query().split("&")).filter(p -> p.startsWith("page=")).findFirst().get().substring(5))).toList();
    }

    /** A straight chain sha(0) .. sha(n); compare sha(0) to sha(n) adds n commits. */
    private CommitGraph chain(int n) {
        var g = new CommitGraph().commit(graph.sha(0), "root");
        for (int i = 1; i <= n; i++) g.commit(graph.sha(i), "commit " + i, graph.sha(i - 1));
        git.respondWith("/api/v3/repositories/9002/compare/", r -> g.githubCompare(r.path(), r.query()));
        return g;
    }

    @Test void compareListsWhatHeadAddsNewestFirst() throws Exception {
        var c = github.compare(remote, "9001", "a000000", "c000000", 1000);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha).containsExactly("c000000", "b000000");
        assertThat(c.truncated()).isFalse();
        assertThat(github.compare(remote, "9001", "c000000", "a000000", 1000).commits()).isEmpty();   // a rollback adds nothing
        var first = git.requests().get(0);
        assertThat(first.path()).isEqualTo("/api/v3/repositories/9001/compare/a000000...c000000");
        assertThat(first.query()).contains("per_page=100", "page=1");
        assertThat(first.headers().get("authorization")).isEqualTo("Bearer ghp-compare");
    }

    @Test void compareReadsTheCommitFields() throws Exception {
        git.respond("/api/v3/repositories/9003/compare/", 200, "cicd/github-compare.json");
        var c = github.compare(remote, "9003", "a000000", "c000000", 1000);
        assertThat(c.commits()).extracting(CicdEvent.Commit::sha)
                .containsExactly("c000000000000000000000000000000000000003", "b000000000000000000000000000000000000002");
        var b = c.commits().get(1);
        assertThat(b.title()).isEqualTo("로그인 #12");
        assertThat(b.message()).startsWith("로그인 #12\n");
        assertThat(b.authorName()).isEqualTo("박준호");
        assertThat(b.authorEmail()).isEqualTo("junho.park@openpms.local");
        assertThat(b.committedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:01:00Z"));   // the author's date
        assertThat(b.webUrl()).endsWith("/commit/b000000000000000000000000000000000000002");
    }

    @Test void pagesUntilTheLimitKeepingTheNewest() throws Exception {
        chain(250);
        var c = github.compare(remote, "9002", graph.sha(0), graph.sha(250), 120);
        assertThat(c.commits()).hasSize(120).extracting(CicdEvent.Commit::sha).first().isEqualTo(graph.sha(250));
        assertThat(c.commits().get(119).sha()).isEqualTo(graph.sha(131));   // the 120 newest: 131..250
        assertThat(c.truncated()).isTrue();
        assertThat(comparePages()).containsExactly(1, 2, 3);   // page 1 for the total, then only the pages holding the newest
    }

    @Test void withMoreThanAThousandTheNewestThousandAreKept() throws Exception {
        chain(1234);
        var c = github.compare(remote, "9002", graph.sha(0), graph.sha(1234), 1000);
        assertThat(c.commits()).hasSize(1000);
        assertThat(c.commits().get(0).sha()).isEqualTo(graph.sha(1234));
        assertThat(c.commits().get(999).sha()).isEqualTo(graph.sha(235));   // 235..1234; the oldest 234 are dropped
        assertThat(c.truncated()).isTrue();
        assertThat(comparePages()).containsExactly(1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
    }

    @Test void aShortRangeIsOneRequest() throws Exception {
        chain(40);
        var c = github.compare(remote, "9002", graph.sha(0), graph.sha(40), 1000);
        assertThat(c.commits()).hasSize(40);
        assertThat(c.truncated()).isFalse();
        assertThat(comparePages()).containsExactly(1);
    }

    @Test void exactlyOneHundredAndOneIsTwoRequestsWithoutTruncation() throws Exception {
        chain(101);
        var c = github.compare(remote, "9002", graph.sha(0), graph.sha(101), 1000);
        assertThat(c.commits()).hasSize(101);
        assertThat(c.truncated()).isFalse();
        assertThat(comparePages()).containsExactly(1, 2);
    }

    @Test void aTooLargeAnswerIsAnIoExceptionAndNotRetried() {
        git.respond("/api/v3/repositories/9004/compare/", 200, "{\"total_commits\":1,\"commits\":[],\"files\":[{\"patch\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}]}");
        assertThatThrownBy(() -> github.compare(remote, "9004", "a000000", "c000000", 1000))
                .isInstanceOf(java.io.IOException.class).hasMessage("response too large");
        assertThat(git.requests()).hasSize(1);
    }

    @Test void aFailingCompareIsAnIoException() {
        assertThatThrownBy(() -> github.compare(remote, "9001", "a000000", "zzzzzzz", 1000))
                .isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 404");
    }

    @Test void containsReadsTheStatus() throws Exception {
        assertThat(github.contains(remote, "9001", "c000000", "b000000")).isTrue();    // ahead
        assertThat(github.contains(remote, "9001", "c000000", "c000000")).isTrue();    // identical
        assertThat(github.contains(remote, "9001", "c000000", "d000000")).isFalse();   // diverged
        assertThat(github.contains(remote, "9001", "a000000", "c000000")).isFalse();   // behind: the commit is newer than head
        var r = git.requests().get(0);
        assertThat(r.path()).isEqualTo("/api/v3/repositories/9001/compare/b000000...c000000");
        assertThat(r.query()).isEqualTo("per_page=1");
    }

    @Test void anUnknownCommitIsNotFound() {
        // a commit deleted since (a force push), or a repository the token lost: told apart by CicdFirstDeploy
        assertThatThrownBy(() -> github.contains(remote, "9001", "c000000", "9999999"))
                .isInstanceOf(NotFound.class).hasMessage("unexpected status 404");
        git.respond("/api/v3/repositories/9005/compare/", 422, "{\"message\":\"No common ancestor\"}");
        assertThatThrownBy(() -> github.contains(remote, "9005", "c000000", "b000000"))
                .isInstanceOf(NotFound.class).hasMessage("unexpected status 422");
    }

    @Test void otherFailuresOfContainsAreIoExceptions() {
        git.respond("/api/v3/repositories/9006/compare/", 500, "{}");
        assertThatThrownBy(() -> github.contains(remote, "9006", "c000000", "b000000"))
                .isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 500");
        git.respond("/api/v3/repositories/9007/compare/", 403, "{}");
        assertThatThrownBy(() -> github.contains(remote, "9007", "c000000", "b000000"))
                .isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 403");
    }

    @Test void pollReadsRunsAndDeployments() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "cicd/github-runs.json");
        git.respond("/api/v3/repositories/9001/deployments", 200, "cicd/github-deployments.json");
        git.respond("/api/v3/repositories/9001/deployments/710692/statuses", 200, "cicd/github-deployment-statuses.json");
        git.respond("/api/v3/repositories/9001/deployments/710600/statuses", 200,
                "[{\"id\":1115000,\"state\":\"inactive\",\"environment\":\"staging\",\"created_at\":\"2026-10-15T10:12:00Z\"}]");
        var polled = github.poll(remote, "9001", OffsetDateTime.parse("2026-10-14T00:00:00Z"));
        assertThat(polled.events()).extracting(CicdEvent::kind).containsExactly("pipeline", "pipeline", "deployment");   // the inactive one is skipped
        assertThat(polled.truncated()).isFalse();
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/actions/runs") && r.query().contains("created=%3E%3D")
                && r.query().contains("per_page=100") && r.query().contains("page=1"));
        // since - 1 day: runs are read by creation time, an older run may have finished since
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/actions/runs") && r.query().contains("created=%3E%3D2026-10-13T00%3A00%3A00Z"));
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/deployments") && r.query().contains("per_page=100") && r.query().contains("page=1"));
        assertThat(git.requests()).anyMatch(r -> r.path().endsWith("/deployments/710692/statuses") && r.query().contains("per_page=1"));
        var failed = polled.events().get(0);
        assertThat(failed.deliveryKey()).isEqualTo("pipeline:9001:30433642:failed:2:2026-10-15T10:04:30Z");
        assertThat(failed.pipeline().status()).isEqualTo("failed");
        assertThat(failed.pipeline().name()).isEqualTo("빌드");
        var running = polled.events().get(1).pipeline();
        assertThat(running.status()).isEqualTo("running");
        assertThat(running.finishedAt()).isNull();
        assertThat(running.sha()).isEqualTo("bcbb5ec396a2c0f828686f14fac9b80b780504f2");   // lower-cased
        var deployed = polled.events().get(2);
        assertThat(deployed.deliveryKey()).isEqualTo("deployment:9001:710692:1115122:2026-10-15T10:12:00Z");
        assertThat(deployed.deployment().status()).isEqualTo("success");
        assertThat(deployed.deployment().sha()).isEqualTo("a91957a858320c0e17f3a0eca7cfacbff50e4b3c");
        assertThat(deployed.deployment().environment()).isEqualTo("staging");
        assertThat(deployed.deployment().webUrl()).endsWith("/jobs/5");
        assertThat(deployed.deployment().deployedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:12:00Z"));
    }

    @Test void theWebhookAndThePollMakeTheSameKeys() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "{\"workflow_runs\":[" + runOf("github-workflow-run.json") + "]}");
        git.respond("/api/v3/repositories/9001/deployments", 200, "[{\"id\":710692,\"sha\":\"a91957a858320c0e17f3a0eca7cfacbff50e4b3c\",\"environment\":\"staging\",\"created_at\":\"2026-10-15T10:10:00Z\"}]");
        git.respond("/api/v3/repositories/9001/deployments/710692/statuses", 200, "cicd/github-deployment-statuses.json");
        var polled = github.poll(remote, "9001", null).events();
        var hookRun = github.parse(java.util.Map.of("x-github-event", "workflow_run"), GitHubBuildsTest.res("github-workflow-run.json").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);
        var hookDeploy = github.parse(java.util.Map.of("x-github-event", "deployment_status"), GitHubBuildsTest.res("github-deployment-status.json").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);
        assertThat(polled).extracting(CicdEvent::deliveryKey).containsExactly(hookRun.deliveryKey(), hookDeploy.deliveryKey());
    }

    private static String runOf(String resource) throws Exception {
        var node = JsonMapper.builder().build().readTree(GitHubBuildsTest.res(resource));
        return node.path("workflow_run").toString();
    }

    @Test void runsAndDeploymentsThatAnswer403Or404CountAsEmpty() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "cicd/github-commits.json");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 403, "{}");
        git.respond("/api/v3/repositories/9001/deployments", 404, "{}");
        var polled = github.poll(remote, "9001", null);
        assertThat(polled.events()).isNotEmpty().allMatch(e -> e.kind().equals("commit"));
        git.respond("/api/v3/repositories/9001/actions/runs", 500, "{}");
        assertThatThrownBy(() -> github.poll(remote, "9001", null)).isInstanceOf(java.io.IOException.class).hasMessage("unexpected status 500");
    }

    @Test void pollWithoutASinceAsksForNoCreatedFilter() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "{\"workflow_runs\":[]}");
        git.respond("/api/v3/repositories/9001/deployments", 200, "[]");
        github.poll(remote, "9001", null);
        assertThat(git.requests()).filteredOn(r -> r.path().endsWith("/actions/runs")).hasSize(1).allMatch(r -> !r.query().contains("created="));
    }

    @Test void theDeploymentListStopsAtOnesOlderThanSinceMinusADay() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "{\"workflow_runs\":[]}");
        git.respond("/api/v3/repositories/9001/deployments", 200,
                "[{\"id\":1,\"sha\":\"a91957a858320c0e17f3a0eca7cfacbff50e4b3c\",\"environment\":\"s\",\"created_at\":\"2026-10-15T10:10:00Z\"},"
                        + "{\"id\":2,\"sha\":\"a91957a858320c0e17f3a0eca7cfacbff50e4b3c\",\"environment\":\"s\",\"created_at\":\"2026-10-01T10:10:00Z\"}]");
        git.respond("/api/v3/repositories/9001/deployments/1/statuses", 200, "cicd/github-deployment-statuses.json");
        git.respond("/api/v3/repositories/9001/deployments/2/statuses", 200, "cicd/github-deployment-statuses.json");
        var polled = github.poll(remote, "9001", OffsetDateTime.parse("2026-10-14T00:00:00Z"));
        assertThat(polled.events()).extracting(CicdEvent::kind).containsExactly("deployment");
        assertThat(git.requests()).noneMatch(r -> r.path().endsWith("/deployments/2/statuses"));
    }

    @Test void atMostThirtyDeploymentsGetTheirStatusReadPerRun() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "{\"workflow_runs\":[]}");
        var list = new StringBuilder("[");
        for (int i = 1; i <= 40; i++) {
            if (i > 1) list.append(',');
            list.append("{\"id\":").append(i).append(",\"sha\":\"a91957a858320c0e17f3a0eca7cfacbff50e4b3c\",\"environment\":\"s\",\"created_at\":\"2026-10-15T10:10:00Z\"}");
        }
        git.respond("/api/v3/repositories/9001/deployments", 200, list.append(']').toString());
        git.respond("/api/v3/repositories/9001/deployments/", 200, "cicd/github-deployment-statuses.json");
        var polled = github.poll(remote, "9001", OffsetDateTime.parse("2026-10-14T00:00:00Z"));
        assertThat(git.requests().stream().filter(r -> r.path().endsWith("/statuses")).count()).isEqualTo(30);
        assertThat(polled.truncated()).isTrue();
    }

    @Test void deploymentsNotUpdatedSinceSinceCostNoStatusRead() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "{\"workflow_runs\":[]}");
        var list = new StringBuilder("[");
        for (int i = 1; i <= 40; i++) {   // created within the day of margin, but not touched since the last poll
            if (i > 1) list.append(',');
            list.append("{\"id\":").append(i).append(",\"sha\":\"a91957a858320c0e17f3a0eca7cfacbff50e4b3c\",\"environment\":\"s\","
                    + "\"created_at\":\"2026-10-13T10:10:00Z\",\"updated_at\":\"2026-10-13T10:10:00Z\"}");
        }
        git.respond("/api/v3/repositories/9001/deployments", 200, list.append(']').toString());
        var polled = github.poll(remote, "9001", OffsetDateTime.parse("2026-10-14T00:00:00Z"));
        assertThat(git.requests()).noneMatch(r -> r.path().endsWith("/statuses"));
        assertThat(polled.truncated()).isFalse();
    }

    @Test void aStatusesBodyThatIsNotAListIsSkipped() throws Exception {
        git.respond("/api/v3/repositories/9001/commits", 200, "[]");
        git.respond("/api/v3/repositories/9001/pulls", 200, "[]");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "{\"workflow_runs\":[]}");
        git.respond("/api/v3/repositories/9001/deployments", 200, "cicd/github-deployments.json");
        git.respond("/api/v3/repositories/9001/deployments/", 200, "{\"message\":\"odd\"}");
        assertThat(github.poll(remote, "9001", OffsetDateTime.parse("2026-10-14T00:00:00Z")).events()).isEmpty();
    }
}

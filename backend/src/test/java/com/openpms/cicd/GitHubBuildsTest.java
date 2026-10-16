package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitHubBuildsTest {
    final GitHubAdapter github = new GitHubAdapter(null, JsonMapper.builder().build());

    static String res(String name) throws Exception {
        try (var in = GitHubBuildsTest.class.getResourceAsStream("/cicd/" + name)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    static byte[] withState(String json, String state) {
        return json.replaceFirst("\"state\": \"success\"", "\"state\": \"" + state + "\"").getBytes(StandardCharsets.UTF_8);
    }

    @Test void workflowRunBecomesAPipeline() throws Exception {
        var e = github.parse(Map.of("x-github-event", "workflow_run"), res("github-workflow-run.json").getBytes(StandardCharsets.UTF_8)).get(0);
        assertThat(e.kind()).isEqualTo("pipeline");
        assertThat(e.repoExternalId()).isEqualTo("9001");
        assertThat(e.pipeline().name()).isEqualTo("빌드");
        assertThat(e.pipeline().status()).isEqualTo("success");
        // the key ends with the run's own update time (seconds) after the status and the attempt
        assertThat(e.deliveryKey()).matches("pipeline:9001:\\d+:(running|success|failed|canceled):\\d+:\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
        assertThat(e.deliveryKey()).isEqualTo("pipeline:9001:30433642:success:1:2026-10-15T10:04:30Z");
        var p = e.pipeline();
        assertThat(p.externalId()).isEqualTo("30433642");
        assertThat(p.sha()).isEqualTo("a91957a858320c0e17f3a0eca7cfacbff50e4b3c");
        assertThat(p.ref()).isEqualTo("main");
        assertThat(p.webUrl()).endsWith("/actions/runs/30433642");
        assertThat(p.startedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:00:05Z"));
        assertThat(p.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:04:30Z"));
        assertThat(p.finishedAt()).isEqualTo(p.updatedAt());
    }

    @Test void conclusionsAreNormalized() throws Exception {
        var text = res("github-workflow-run.json");
        var expected = Map.of("success", "success", "failure", "failed", "timed_out", "failed", "startup_failure", "failed",
                "action_required", "failed", "cancelled", "canceled", "skipped", "canceled", "stale", "canceled", "neutral", "canceled");
        for (var c : expected.entrySet()) {
            var body = text.replaceFirst("\"conclusion\": \"[a-z_]+\"", "\"conclusion\": \"" + c.getKey() + "\"").getBytes(StandardCharsets.UTF_8);
            assertThat(github.parse(Map.of("x-github-event", "workflow_run"), body).get(0).pipeline().status()).as(c.getKey()).isEqualTo(c.getValue());
        }
        var running = text.replaceFirst("\"status\": \"completed\"", "\"status\": \"in_progress\"").replaceFirst("\"conclusion\": \"[a-z_]+\"", "\"conclusion\": null");
        var p = github.parse(Map.of("x-github-event", "workflow_run"), running.getBytes(StandardCharsets.UTF_8)).get(0).pipeline();
        assertThat(p.status()).isEqualTo("running");
        assertThat(p.finishedAt()).isNull();
        var queued = text.replaceFirst("\"status\": \"completed\"", "\"status\": \"queued\"").replaceFirst("\"conclusion\": \"[a-z_]+\"", "\"conclusion\": null");
        assertThat(github.parse(Map.of("x-github-event", "workflow_run"), queued.getBytes(StandardCharsets.UTF_8)).get(0).pipeline().status()).isEqualTo("running");
        var odd = text.replaceFirst("\"conclusion\": \"[a-z_]+\"", "\"conclusion\": \"mystery\"");
        assertThat(github.parse(Map.of("x-github-event", "workflow_run"), odd.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test void aRetryOfTheSameRunGetsAnotherKey() throws Exception {
        var text = res("github-workflow-run.json");
        var again = text.replace("\"run_attempt\": 1", "\"run_attempt\": 2").replace("10:04:30Z", "10:09:00Z");
        var a = github.parse(Map.of("x-github-event", "workflow_run"), text.getBytes(StandardCharsets.UTF_8)).get(0).deliveryKey();
        var b = github.parse(Map.of("x-github-event", "workflow_run"), again.getBytes(StandardCharsets.UTF_8)).get(0).deliveryKey();
        assertThat(b).isNotEqualTo(a).startsWith("pipeline:9001:30433642:success:2:");
    }

    @Test void aWorkflowRunWithoutAnIdOrAHexShaIsIgnored() throws Exception {
        var text = res("github-workflow-run.json");
        assertThat(github.parse(Map.of("x-github-event", "workflow_run"),
                text.replace("\"head_sha\": \"a91957a858320c0e17f3a0eca7cfacbff50e4b3c\"", "\"head_sha\": \"zzz\"").getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(github.parse(Map.of("x-github-event", "workflow_run"),
                text.replace("\"id\": 30433642,", "").getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test void deploymentStatusBecomesADeployment() throws Exception {
        var e = github.parse(Map.of("x-github-event", "deployment_status"), res("github-deployment-status.json").getBytes(StandardCharsets.UTF_8)).get(0);
        assertThat(e.kind()).isEqualTo("deployment");
        assertThat(e.repoExternalId()).isEqualTo("9001");
        assertThat(e.deployment().status()).isEqualTo("success");
        assertThat(e.deployment().sha()).matches("[0-9a-f]{40}");
        assertThat(e.deployment().environment()).isEqualTo("staging");
        assertThat(e.deployment().externalId()).isEqualTo("710692");
        assertThat(e.deployment().webUrl()).endsWith("/actions/runs/30433642/jobs/5");   // log_url wins over target_url
        assertThat(e.deployment().deployedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:12:00Z"));
        assertThat(e.deliveryKey()).isEqualTo("deployment:9001:710692:1115122:2026-10-15T10:12:00Z");
        assertThat(github.parse(Map.of("x-github-event", "deployment_status"), withState(res("github-deployment-status.json"), "error")).get(0)
                .deployment().status()).isEqualTo("failed");
        for (var s : java.util.List.of("failure", "error")) {
            assertThat(github.parse(Map.of("x-github-event", "deployment_status"), withState(res("github-deployment-status.json"), s)).get(0)
                    .deployment().status()).as(s).isEqualTo("failed");
        }
        for (var s : java.util.List.of("pending", "queued", "in_progress")) {
            assertThat(github.parse(Map.of("x-github-event", "deployment_status"), withState(res("github-deployment-status.json"), s)).get(0)
                    .deployment().status()).as(s).isEqualTo("running");
        }
    }

    @Test void deploymentFallsBackToTheDeploymentsEnvironmentAndTargetUrl() throws Exception {
        var text = res("github-deployment-status.json")
                .replace("\"environment\": \"staging\",\n    \"description\"", "\"description\"")
                .replace("\"log_url\": \"https://github.com/openpms-test/web/actions/runs/30433642/jobs/5\",", "");
        var d = github.parse(Map.of("x-github-event", "deployment_status"), text.getBytes(StandardCharsets.UTF_8)).get(0).deployment();
        assertThat(d.environment()).isEqualTo("staging");   // from the deployment
        assertThat(d.webUrl()).isEqualTo("https://staging.example.com");
    }

    @Test void inactiveDeploymentStatusIsIgnored() throws Exception {
        assertThat(github.parse(Map.of("x-github-event", "deployment_status"), withState(res("github-deployment-status.json"), "inactive"))).isEmpty();
    }

    @Test void aMergedPullRequestCarriesItsMergeCommitAnOpenOneDoesNot() throws Exception {
        var open = github.parse(Map.of("x-github-event", "pull_request"), res("github-pr.json").getBytes(StandardCharsets.UTF_8)).get(0).mr();
        assertThat(open.state()).isEqualTo("open");
        assertThat(open.mergeCommitSha()).isNull();   // GitHub's value on an open pull request is only a test merge
        var merged = res("github-pr.json").replace("\"state\": \"open\"", "\"state\": \"closed\"")
                .replace("\"merged_at\": null", "\"merged_at\": \"2026-10-15T11:00:00+09:00\"");
        var mr = github.parse(Map.of("x-github-event", "pull_request"), merged.getBytes(StandardCharsets.UTF_8)).get(0).mr();
        assertThat(mr.state()).isEqualTo("merged");
        assertThat(mr.mergeCommitSha()).isEqualTo("e4c3a2b1d0f9e8d7c6b5a4938271605f4e3d2c1b");
    }
}

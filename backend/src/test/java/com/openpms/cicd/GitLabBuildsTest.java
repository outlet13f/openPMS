package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitLabBuildsTest {
    final GitLabAdapter gitlab = new GitLabAdapter(null, JsonMapper.builder().build());

    static byte[] res(String name) throws Exception {
        try (var in = GitLabBuildsTest.class.getResourceAsStream("/cicd/" + name)) { return in.readAllBytes(); }
    }

    static String text(String name) throws Exception {
        return new String(res(name), StandardCharsets.UTF_8);
    }

    @Test void pipelineHook() throws Exception {
        var e = gitlab.parse(Map.of("x-gitlab-event", "Pipeline Hook"), res("gitlab-pipeline.json")).get(0);
        assertThat(e.kind()).isEqualTo("pipeline");
        assertThat(e.repoExternalId()).isEqualTo("101");
        assertThat(e.deliveryKey()).isEqualTo("pipeline:101:31:success");
        var p = e.pipeline();
        assertThat(p.externalId()).isEqualTo("31");
        assertThat(p.name()).isEqualTo("CI");
        assertThat(p.sha()).isEqualTo("bcbb5ec396a2c0f828686f14fac9b80b780504f2");
        assertThat(p.ref()).isEqualTo("feature/12-login");
        assertThat(p.status()).isEqualTo("success");
        assertThat(p.startedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:00:00Z"));
        assertThat(p.finishedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T10:07:30Z"));
        assertThat(p.updatedAt()).isEqualTo(p.finishedAt());
        assertThat(p.webUrl()).endsWith("/-/pipelines/31");
        assertThat(e.commit()).isNull();
        assertThat(e.deployment()).isNull();
    }

    @Test void pipelineStatesAreNormalized() throws Exception {
        var text = text("gitlab-pipeline.json");
        var expected = Map.of("created", "running", "pending", "running", "running", "running", "manual", "running",
                "failed", "failed", "canceled", "canceled", "skipped", "canceled");
        for (var s : expected.entrySet()) {
            var body = text.replace("\"status\": \"success\"", "\"status\": \"" + s.getKey() + "\"").getBytes(StandardCharsets.UTF_8);
            assertThat(gitlab.parse(Map.of("x-gitlab-event", "Pipeline Hook"), body).get(0).pipeline().status()).as(s.getKey()).isEqualTo(s.getValue());
        }
        for (var s : java.util.List.of("waiting_for_resource", "preparing", "scheduled")) {
            var body = text.replace("\"status\": \"success\"", "\"status\": \"" + s + "\"").getBytes(StandardCharsets.UTF_8);
            assertThat(gitlab.parse(Map.of("x-gitlab-event", "Pipeline Hook"), body).get(0).pipeline().status()).as(s).isEqualTo("running");
        }
        var odd = text.replace("\"status\": \"success\"", "\"status\": \"mystery\"").getBytes(StandardCharsets.UTF_8);
        assertThat(gitlab.parse(Map.of("x-gitlab-event", "Pipeline Hook"), odd)).isEmpty();
    }

    @Test void runningPipelineWithoutAnUrlOrNameFallsBack() throws Exception {
        var body = text("gitlab-pipeline.json").replace("\"status\": \"success\"", "\"status\": \"running\"")
                .replace("\"finished_at\": \"2026-10-15 10:07:30 UTC\"", "\"finished_at\": null")
                .replaceAll("\"url\": \"[^\"]*\"", "\"url\": \"\"").replace("\"name\": \"CI\", ", "");
        var p = gitlab.parse(Map.of("x-gitlab-event", "Pipeline Hook"), body.getBytes(StandardCharsets.UTF_8)).get(0).pipeline();
        assertThat(p.name()).isEmpty();
        assertThat(p.finishedAt()).isNull();
        assertThat(p.updatedAt()).isEqualTo(p.startedAt());
        assertThat(p.webUrl()).isEqualTo("https://gitlab.example.com/web/portal/-/pipelines/31");
    }

    @Test void pipelineWithoutAShaMakesNoEvent() throws Exception {
        var body = text("gitlab-pipeline.json").replace("bcbb5ec396a2c0f828686f14fac9b80b780504f2", "");
        assertThat(gitlab.parse(Map.of("x-gitlab-event", "Pipeline Hook"), body.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test void deploymentHookTakesTheFullShaFromTheCommitUrl() throws Exception {
        var e = gitlab.parse(Map.of("x-gitlab-event", "Deployment Hook"), res("gitlab-deployment.json")).get(0);
        assertThat(e.kind()).isEqualTo("deployment");
        assertThat(e.repoExternalId()).isEqualTo("101");
        assertThat(e.deliveryKey()).isEqualTo("deployment:101:15:success");
        var d = e.deployment();
        assertThat(d.externalId()).isEqualTo("15");
        assertThat(d.sha()).isEqualTo("bcbb5ec396a2c0f828686f14fac9b80b780504f2");
        assertThat(d.environment()).isEqualTo("staging");
        assertThat(d.status()).isEqualTo("success");
        assertThat(d.deployedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T02:20:00Z"));
        assertThat(d.webUrl()).endsWith("/-/jobs/796");
    }

    @Test void deploymentWithoutACommitUrlKeepsTheShortSha() throws Exception {
        var text = text("gitlab-deployment.json").replaceAll("\"commit_url\": \"[^\"]*\"", "\"commit_url\": \"\"").replace("bcbb5ec3\"", "BCBB5EC3\"");
        var d = gitlab.parse(Map.of("x-gitlab-event", "Deployment Hook"), text.getBytes(StandardCharsets.UTF_8)).get(0).deployment();
        assertThat(d.sha()).isEqualTo("bcbb5ec3");
    }

    @Test void deploymentStatesAreNormalized() throws Exception {
        var text = text("gitlab-deployment.json");
        var expected = Map.of("created", "running", "running", "running", "failed", "failed", "canceled", "failed");
        for (var s : expected.entrySet()) {
            var body = text.replace("\"status\": \"success\"", "\"status\": \"" + s.getKey() + "\"").getBytes(StandardCharsets.UTF_8);
            var e = gitlab.parse(Map.of("x-gitlab-event", "Deployment Hook"), body).get(0);
            assertThat(e.deployment().status()).as(s.getKey()).isEqualTo(s.getValue());
            assertThat(e.deliveryKey()).as(s.getKey()).isEqualTo("deployment:101:15:" + s.getKey());
        }
        var odd = text.replace("\"status\": \"success\"", "\"status\": \"blocked\"").getBytes(StandardCharsets.UTF_8);
        assertThat(gitlab.parse(Map.of("x-gitlab-event", "Deployment Hook"), odd)).isEmpty();
    }

    @Test void openedMergeRequestHasNoMergeCommit() throws Exception {
        var e = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), res("gitlab-mr.json")).get(0);
        assertThat(e.mr().state()).isEqualTo("opened");
        assertThat(e.mr().mergeCommitSha()).isNull();
    }

    @Test void mergedMergeRequestCarriesItsMergeCommit() throws Exception {
        var merged = text("gitlab-mr.json").replace("\"state\": \"opened\"", "\"state\": \"merged\"")
                .replace("\"merge_commit_sha\": null", "\"merge_commit_sha\": \"ABCDEF0123456789abcdef0123456789abcdef01\"");
        var e = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), merged.getBytes(StandardCharsets.UTF_8)).get(0);
        assertThat(e.mr().state()).isEqualTo("merged");
        assertThat(e.mr().mergeCommitSha()).isEqualTo("abcdef0123456789abcdef0123456789abcdef01");
    }

    @Test void squashCommitWinsOverTheMergeCommit() throws Exception {
        var merged = text("gitlab-mr.json").replace("\"state\": \"opened\"", "\"state\": \"merged\"")
                .replace("\"merge_commit_sha\": null", "\"merge_commit_sha\": \"abcdef0123456789abcdef0123456789abcdef01\"")
                .replace("\"squash_commit_sha\": null", "\"squash_commit_sha\": \"1234567890abcdef1234567890abcdef12345678\"");
        var e = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), merged.getBytes(StandardCharsets.UTF_8)).get(0);
        assertThat(e.mr().mergeCommitSha()).isEqualTo("1234567890abcdef1234567890abcdef12345678");
    }

    @Test void aClosedMergeRequestKeepsNoMergeCommit() throws Exception {
        var closed = text("gitlab-mr.json").replace("\"state\": \"opened\"", "\"state\": \"closed\"")
                .replace("\"merge_commit_sha\": null", "\"merge_commit_sha\": \"abcdef0123456789abcdef0123456789abcdef01\"");
        var e = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), closed.getBytes(StandardCharsets.UTF_8)).get(0);
        assertThat(e.mr().mergeCommitSha()).isNull();
    }
}

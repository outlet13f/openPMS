package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AdaptersTest {
    static byte[] res(String name) throws Exception {
        try (var in = AdaptersTest.class.getResourceAsStream("/cicd/" + name)) { return in.readAllBytes(); }
    }

    static String hmac(byte[] body, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    final GitHubAdapter github = new GitHubAdapter(null, tools.jackson.databind.json.JsonMapper.builder().build());
    final GitLabAdapter gitlab = new GitLabAdapter(null, tools.jackson.databind.json.JsonMapper.builder().build());

    @Test void githubSignature() throws Exception {
        var body = res("github-push.json");
        assertThat(github.verify(Map.of("x-hub-signature-256", hmac(body, "s3cret")), body, "s3cret")).isTrue();
        assertThat(github.verify(Map.of("x-hub-signature-256", hmac(body, "other")), body, "s3cret")).isFalse();
        assertThat(github.verify(Map.of(), body, "s3cret")).isFalse();
    }

    @Test void gitlabToken() throws Exception {
        var body = res("gitlab-push.json");
        assertThat(gitlab.verify(Map.of("x-gitlab-token", "s3cret"), body, "s3cret")).isTrue();
        assertThat(gitlab.verify(Map.of("x-gitlab-token", "s3cre"), body, "s3cret")).isFalse();
        assertThat(gitlab.verify(Map.of(), body, "s3cret")).isFalse();
    }

    @Test void githubPushBecomesCommitEvents() throws Exception {
        var events = github.parse(Map.of("x-github-event", "push"), res("github-push.json"));
        assertThat(events).hasSize(1);
        var e = events.get(0);
        assertThat(e.kind()).isEqualTo("commit");
        assertThat(e.repoExternalId()).isEqualTo("9001");
        assertThat(e.deliveryKey()).isEqualTo("commit:9001:1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d");
        assertThat(e.commit().title()).isEqualTo("로그인 오류 고침 #12");
        assertThat(e.commit().authorEmail()).isEqualTo("junho.park@openpms.local");
    }

    @Test void gitlabPushBecomesCommitEvents() throws Exception {
        var e = gitlab.parse(Map.of("x-gitlab-event", "Push Hook"), res("gitlab-push.json")).get(0);
        assertThat(e.deliveryKey()).isEqualTo("commit:4201:9f8e7d6c5b4a39281706f5e4d3c2b1a098765432");
        assertThat(e.commit().title()).isEqualTo("로그인 오류 고침 #12");
        assertThat(e.commit().webUrl()).contains("9f8e7d6c");
    }

    @Test void pullRequestStates() throws Exception {
        var e = github.parse(Map.of("x-github-event", "pull_request"), res("github-pr.json")).get(0);
        assertThat(e.kind()).isEqualTo("merge_request");
        assertThat(e.mr().state()).isIn("open", "draft", "merged", "closed");
        assertThat(e.mr().sourceBranch()).isEqualTo("feature/12-login");
        assertThat(e.deliveryKey()).isEqualTo("mr:9001:7:2026-10-15T01:30:00Z");
    }

    @Test void gitlabMergeRequest() throws Exception {
        var e = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), res("gitlab-mr.json")).get(0);
        assertThat(e.mr().sourceBranch()).isNotBlank();
        assertThat(e.mr().state()).isEqualTo("opened");
        assertThat(e.deliveryKey()).matches("mr:\\d+:\\d+:\\d{4}-.*Z");
        assertThat(e.deliveryKey()).isEqualTo("mr:4201:7:2026-10-15T01:30:00Z");
    }

    @Test void gitlabNamesTheHookUserAsAuthorOnlyWhenTheMergeRequestOpens() throws Exception {
        var opened = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), res("gitlab-mr.json")).get(0);
        assertThat(opened.mr().authorName()).isEqualTo("박준호");
        assertThat(opened.mr().authorEmail()).isEqualTo("junho.park@openpms.local");
        // an update: "user" is the reviewer who acted, not the author
        var update = new String(res("gitlab-mr.json"), java.nio.charset.StandardCharsets.UTF_8).replace("\"action\": \"open\"", "\"action\": \"update\"")
                .replace("\"name\": \"박준호\", \"email\": \"junho.park@openpms.local\"", "\"name\": \"최민지\", \"email\": \"minji.choi@openpms.local\"");
        var e = gitlab.parse(Map.of("x-gitlab-event", "Merge Request Hook"), update.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);
        assertThat(e.mr().authorName()).isEmpty();
        assertThat(e.mr().authorEmail()).isEmpty();
        assertThat(e.deliveryKey()).isEqualTo(opened.deliveryKey());
    }

    @Test void unknownEventsAreIgnored() throws Exception {
        assertThat(github.parse(Map.of("x-github-event", "ping"), "{}".getBytes())).isEmpty();
        assertThat(gitlab.parse(Map.of("x-gitlab-event", "Note Hook"), "{}".getBytes())).isEmpty();
    }
}

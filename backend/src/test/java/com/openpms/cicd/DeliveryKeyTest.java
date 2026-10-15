package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A webhook and the polling job must give the same delivery key for the same fact. */
class DeliveryKeyTest {
    static CicdHttp stub(String path, String file) {
        return new CicdHttp() {
            @Override public HttpResult get(String url, Map<String, String> headers) {
                try {
                    var uri = java.net.URI.create(url).getPath();
                    return new HttpResult(200, uri.endsWith(path) ? AdaptersTest.res(file) : "[]".getBytes());
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
    }

    @Test void gitlabMergeRequestKeyMatchesAcrossWebhookAndPoll() throws Exception {
        var json = JsonMapper.builder().build();
        var viaWebhook = new GitLabAdapter(null, json).parse(Map.of("x-gitlab-event", "Merge Request Hook"), AdaptersTest.res("gitlab-mr.json")).get(0);
        var polling = new GitLabAdapter(stub("/merge_requests", "gitlab-mrs.json"), json);
        var viaPoll = polling.poll(new CicdAdapter.Remote("https://gitlab.example.com", "t"), "4201", OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        var mr = viaPoll.stream().filter(e -> e.kind().equals("merge_request")).findFirst().orElseThrow();
        // webhook: "2026-10-15 10:30:00 +0900"; REST: "2026-10-15T01:30:00.123Z"
        assertThat(mr.deliveryKey()).isEqualTo(viaWebhook.deliveryKey()).isEqualTo("mr:4201:7:2026-10-15T01:30:00Z");
    }

    @Test void githubPullKeyMatchesAcrossWebhookAndPoll() throws Exception {
        var json = JsonMapper.builder().build();
        var viaWebhook = new GitHubAdapter(null, json).parse(Map.of("x-github-event", "pull_request"), AdaptersTest.res("github-pr.json")).get(0);
        var polling = new GitHubAdapter(stub("/pulls", "github-pulls.json"), json);
        var pulls = polling.poll(new CicdAdapter.Remote("https://github.com", "t"), "9001", OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        var mr = pulls.stream().filter(e -> e.kind().equals("merge_request")).toList();
        assertThat(mr).hasSize(1); // the older pull request stops the walk
        assertThat(mr.get(0).deliveryKey()).isEqualTo(viaWebhook.deliveryKey());
        assertThat(viaWebhook.deliveryKey()).isEqualTo("mr:9001:7:2026-10-15T01:30:00Z");
    }
}

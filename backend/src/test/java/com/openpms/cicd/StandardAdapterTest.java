package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StandardAdapterTest {
    final JsonMapper json = JsonMapper.builder().build();
    final JenkinsAdapter jenkins = new JenkinsAdapter(json);
    final ArgoCdAdapter argo = new ArgoCdAdapter(json);

    static String hmac(byte[] body, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    static byte[] body(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static String resource(String name) throws Exception {
        try (var in = StandardAdapterTest.class.getResourceAsStream("/cicd/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    static final String DEPLOY = "{\"type\":\"deployment\",\"repository\":\"Web/Portal\",\"id\":\"web-portal-42\",\"image\":\"registry.local/web/portal:1a2b3c4d\","
            + "\"environment\":\"production\",\"status\":\"success\",\"url\":\"https://jenkins.local/job/portal/42/\",\"at\":\"2026-10-15T09:00:00+09:00\"}";

    @Test void jenkinsNeedsTheSignature() throws Exception {
        var b = body(DEPLOY);
        assertThat(jenkins.verify(Map.of("x-openpms-signature", hmac(b, "s3cret")), b, "s3cret")).isTrue();
        assertThat(jenkins.verify(Map.of("x-openpms-signature", hmac(b, "other")), b, "s3cret")).isFalse();
        assertThat(jenkins.verify(Map.of("x-openpms-token", "s3cret"), b, "s3cret")).isFalse();   // Jenkins can sign, so it must
        assertThat(jenkins.verify(Map.of(), b, "s3cret")).isFalse();
        assertThat(jenkins.verify(Map.of("x-openpms-signature", hmac(b, "s3cret")), body(DEPLOY + " "), "s3cret")).isFalse();   // body changed
        assertThat(jenkins.verify(Map.of("x-openpms-signature", "sha256="), b, "")).isFalse();   // no empty secret
    }

    @Test void argoMayUseTheFixedTokenHeader() throws Exception {
        var b = body(DEPLOY);
        assertThat(argo.verify(Map.of("x-openpms-token", "s3cret"), b, "s3cret")).isTrue();
        assertThat(argo.verify(Map.of("x-openpms-token", "s3cre"), b, "s3cret")).isFalse();
        assertThat(argo.verify(Map.of("x-openpms-token", ""), b, "")).isFalse();
        assertThat(argo.verify(Map.of(), b, "s3cret")).isFalse();
        assertThat(argo.verify(Map.of("x-openpms-signature", hmac(b, "s3cret")), b, "s3cret")).isTrue();
        assertThat(argo.verify(Map.of("x-openpms-signature", hmac(b, "other")), b, "s3cret")).isFalse();
    }

    @Test void deploymentNotice() throws Exception {
        var e = jenkins.parse(Map.of(), body(resource("standard-deployment.json"))).get(0);
        assertThat(e.kind()).isEqualTo("deployment");
        assertThat(e.repoExternalId()).isNull();
        assertThat(e.repoPath()).isEqualTo("Web/Portal");
        assertThat(e.deliveryKey()).isEqualTo("deployment:std:web/portal:web-portal-42:success:2026-10-15T00:00:00Z");   // Ruling 31
        assertThat(e.deployment().sha()).isNull();
        assertThat(e.deployment().image()).endsWith(":1a2b3c4d");
        assertThat(e.deployment().environment()).isEqualTo("production");
        assertThat(e.deployment().deployedAt().toInstant()).isEqualTo(java.time.Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(e.deployment().webUrl()).isEqualTo("https://jenkins.local/job/portal/42/");
    }

    @Test void deploymentMayCarryAShaAndRepeatedStatesGetOwnKeys() {
        var withSha = DEPLOY.replace("\"id\"", "\"sha\":\"ABCDEF1\",\"id\"");
        assertThat(argo.parse(Map.of(), body(withSha)).get(0).deployment().sha()).isEqualTo("abcdef1");
        var later = DEPLOY.replace("09:00:00+09:00", "09:05:00+09:00");
        assertThat(argo.parse(Map.of(), body(later)).get(0).deliveryKey())
                .isNotEqualTo(argo.parse(Map.of(), body(DEPLOY)).get(0).deliveryKey());
        assertThat(argo.parse(Map.of(), body(DEPLOY.replace("\"status\":\"success\"", "\"status\":\"canceled\"")))).isEmpty();   // deployments have no canceled
    }

    @Test void pipelineNoticeNeedsASha() throws Exception {
        var ok = resource("standard-pipeline.json").replace("1a2b3c4d5e6f", "1A2B3C4D5E6F");
        var e = jenkins.parse(Map.of(), body(ok)).get(0);
        var p = e.pipeline();
        assertThat(e.deliveryKey()).isEqualTo("pipeline:std:web/portal:portal#42:failed:2026-10-15T00:10:00Z");
        assertThat(p.name()).isEqualTo("portal");
        assertThat(p.sha()).isEqualTo("1a2b3c4d5e6f");
        assertThat(p.status()).isEqualTo("failed");
        assertThat(p.startedAt()).isNull();
        assertThat(p.finishedAt()).isEqualTo(p.updatedAt());
        var running = jenkins.parse(Map.of(), body(ok.replace("\"failed\"", "\"running\""))).get(0).pipeline();
        assertThat(running.startedAt()).isEqualTo(running.updatedAt());
        assertThat(running.finishedAt()).isNull();
        assertThat(jenkins.parse(Map.of(), body(ok.replace("\"sha\":\"1A2B3C4D5E6F\",", "")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(ok.replace("1A2B3C4D5E6F", "xyz")))).isEmpty();   // wrong shape = no sha = no build
        assertThat(argo.parse(Map.of(), body(ok))).isEmpty();   // Argo CD sends deployments only
    }

    @Test void malformedNoticesGiveNothing() {
        assertThat(jenkins.parse(Map.of(), body("not json"))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body("[]"))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(""))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"status\":\"success\"", "\"status\":\"done\"")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"environment\":\"production\",", "")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"type\":\"deployment\"", "\"type\":\"other\"")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("Web/Portal", "x".repeat(301))))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("Web/Portal", "x".repeat(300))))).hasSize(1);
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("web-portal-42", "x".repeat(201))))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("web-portal-42", " ")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("production", "x".repeat(101))))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("registry.local/web/portal:1a2b3c4d", "x".repeat(501))))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"id\"", "\"name\":\"" + "x".repeat(201) + "\",\"id\"")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"id\"", "\"ref\":\"" + "x".repeat(256) + "\",\"id\"")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"id\":\"web-portal-42\"", "\"id\":42")))).isEmpty();   // wrong type
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"id\"", "\"name\":{\"a\":1},\"id\"")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"at\":\"2026-10-15T09:00:00+09:00\"", "\"at\":\"yesterday\"")))).isEmpty();
        assertThat(jenkins.parse(Map.of(), body(DEPLOY.replace("\"at\":\"2026-10-15T09:00:00+09:00\"", "\"at\":null")))).isEmpty();
    }

    @Test void nameIsOptionalAndDefaultsToEmpty() {
        var p = "{\"type\":\"pipeline\",\"repository\":\"a/b\",\"id\":\"1\",\"sha\":\"abcdef1\",\"status\":\"success\",\"at\":\"2026-10-15T00:10:00Z\"}";
        var e = jenkins.parse(Map.of(), body(p)).get(0).pipeline();
        assertThat(e.name()).isEmpty();
        assertThat(e.ref()).isEmpty();
        assertThat(e.webUrl()).isEmpty();
    }

    @Test void noRemoteWork() throws Exception {
        assertThat(jenkins.listRepositories(null, "x")).isEmpty();
        assertThat(jenkins.repository(null, "1")).isNull();
        assertThat(jenkins.poll(null, "1", null).events()).isEmpty();
        assertThat(jenkins.check(null)).isZero();
    }
}

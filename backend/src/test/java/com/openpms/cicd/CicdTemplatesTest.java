package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CicdTemplatesTest {
    static final String HOOK = "https://pms.example.com/api/v1/hooks/cicd/abc";

    @Test void jenkinsfileSignsAndPostsToTheHook() {
        var t = CicdTemplates.jenkinsfile(HOOK);
        assertThat(t).contains(HOOK, "X-OpenPMS-Signature: sha256=", "openssl dgst -sha256 -hmac", "<웹훅 비밀>", "withCredentials")
                .doesNotContain("{{WEBHOOK_URL}}");
        // a multibranch job's branch name as the host has it; the plain Git plugin's GIT_BRANCH is "origin/x"
        assertThat(t).contains("ref: env.BRANCH_NAME ?: env.GIT_BRANCH");
    }

    @Test void argoTemplateSendsTheImageNotTheManifestRevision() {
        var t = CicdTemplates.argocd(HOOK);
        assertThat(t).contains(HOOK, "X-OpenPMS-Token", "<웹훅 비밀>", "openpms.repository", "openpms.environment", ".app.status.summary.images")
                .doesNotContain("syncResult.revision").doesNotContain("\"sha\"").doesNotContain("{{WEBHOOK_URL}}");
        // what Argo CD would send, through the standard adapter: the commit comes from the image tag only
        var body = CicdTemplates.argoSampleBody("web/portal", "production", "registry.local/web/portal:9f8e7d6c", "2026-10-15T01:00:00Z");
        var e = new ArgoCdAdapter(JsonMapper.builder().build()).parse(Map.of(), body.getBytes(StandardCharsets.UTF_8)).get(0);
        assertThat(e.deployment().sha()).isNull();
        assertThat(ImageTags.sha(e.deployment().image(), null)).contains("9f8e7d6c");
        assertThat(e.deployment().environment()).isEqualTo("production");
    }

    @Test void argoTemplateReferencesTheSecretNeverContainsIt() {
        var t = CicdTemplates.argocd(HOOK);
        // the ConfigMap header points at the Argo CD notifications secret; the placeholder only sits in the Secret example
        assertThat(t).contains("value: $openpms-webhook-secret").contains("argocd-notifications-secret");
        var configMap = t.substring(t.indexOf("kind: ConfigMap"));
        assertThat(configMap).doesNotContain("<웹훅 비밀>");
    }
}

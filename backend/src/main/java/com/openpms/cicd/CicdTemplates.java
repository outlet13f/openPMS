package com.openpms.cicd;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The text an administrator pastes into Jenkins or Argo CD. The webhook secret never goes in: only a placeholder or a reference. */
public final class CicdTemplates {
    private static final String PLACE = "{{WEBHOOK_URL}}";

    private CicdTemplates() {}

    public static String jenkinsfile(String webhookUrl) {
        return load("jenkinsfile.txt").replace(PLACE, webhookUrl);
    }

    public static String argocd(String webhookUrl) {
        return load("argocd.yaml").replace(PLACE, webhookUrl);
    }

    /** The body of the success template with Argo CD's template variables replaced by sample values (what Argo CD would send). */
    static String argoSampleBody(String repository, String environment, String image, String at) {
        var lines = load("argocd.yaml").split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].trim().equals("body: |")) continue;
            return lines[i + 1].trim()
                    .replace("{{index .app.metadata.annotations \"openpms.repository\"}}", repository)
                    .replace("{{index .app.metadata.annotations \"openpms.environment\"}}", environment)
                    .replace("{{index .app.status.summary.images 0}}", image)
                    .replace("{{.app.metadata.name}}", "portal")
                    .replace("{{.app.status.operationState.startedAt}}", at)
                    .replace("{{.app.status.operationState.finishedAt}}", at)
                    .replace("{{.context.argocdUrl}}", "https://argocd.local");
        }
        throw new IllegalStateException("argocd.yaml has no body");
    }

    private static String load(String name) {
        try (var in = CicdTemplates.class.getResourceAsStream("/cicd/templates/" + name)) {
            if (in == null) throw new IllegalStateException("missing template " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.openpms.cicd;

import static com.openpms.cicd.AdapterSupport.same;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Argo CD: its notification webhook cannot sign a body, so a fixed X-OpenPMS-Token header with the secret is accepted too. Deployments only. */
@Component
public class ArgoCdAdapter extends StandardAdapter {
    public ArgoCdAdapter(JsonMapper json) {
        super(json);
    }

    @Override public String kind() { return "argocd"; }

    @Override protected boolean acceptsPipelines() { return false; }

    @Override public boolean verify(Map<String, String> headers, byte[] body, String secret) {
        var token = headers.get("x-openpms-token");
        if (token != null && secret != null && !secret.isEmpty() && same(token, secret)) return true;
        return signatureMatches(headers, body, secret);
    }
}

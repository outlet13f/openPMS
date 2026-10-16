package com.openpms.cicd;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Jenkins: a Jenkinsfile step signs the notice (HMAC) and posts it. */
@Component
public class JenkinsAdapter extends StandardAdapter {
    public JenkinsAdapter(JsonMapper json) {
        super(json);
    }

    @Override public String kind() { return "jenkins"; }
}

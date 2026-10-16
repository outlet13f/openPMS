package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CicdLinksTest {
    @Test void sameOriginOnly() {
        var base = "https://gitlab.example.com/web/portal";
        assertThat(CicdLinks.sameOrigin("https://gitlab.example.com/web/portal/-/pipelines/31", base)).endsWith("/pipelines/31");
        assertThat(CicdLinks.sameOrigin("https://GITLAB.example.com:443/x", base)).isNotEmpty();
        assertThat(CicdLinks.sameOrigin("http://gitlab.example.com/x", base)).isEmpty();          // scheme
        assertThat(CicdLinks.sameOrigin("https://gitlab.example.com:8443/x", base)).isEmpty();    // port
        assertThat(CicdLinks.sameOrigin("https://evil.example/x", base)).isEmpty();
        assertThat(CicdLinks.sameOrigin("https://u:p@gitlab.example.com/x", base)).isEmpty();     // user part
        assertThat(CicdLinks.sameOrigin("javascript:alert(1)", base)).isEmpty();
        assertThat(CicdLinks.sameOrigin(null, base)).isEmpty();
        assertThat(CicdLinks.sameOrigin("https://jenkins.local/job/1", null)).isEmpty();
    }
}

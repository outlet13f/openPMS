package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** The development tab's verification line: the work package's current result on the default source, only when there is one. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class DevTabVerificationIT {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    ReleaseFixture r;
    long wp;
    UUID commit;

    @BeforeEach void setUp() throws Exception {
        r = new ReleaseFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(), db);
        r.f.addMember(r.admin, r.project, CicdFixture.JUNHO, CicdFixture.ROLE_MEMBER);
        wp = r.wp();
        commit = r.commit(r.sha(), T0, wp);
        r.deploy("스테이징", commit, T0.plusHours(1));
    }

    JsonNode lines() throws Exception {
        return r.f.body(r.f.mvc.perform(get("/api/v1/work-packages/" + wp + "/cicd").session(r.f.login(CicdFixture.JUNHO_EMAIL)))
                .andExpect(status().isOk()).andReturn()).get("verifications");
    }

    void pass(OffsetDateTime at) {
        r.verification(wp, "스테이징", "passed", "", at, r.addedAt("스테이징", commit));
    }

    @Test void showsTheCurrentStagingVerification() throws Exception {
        pass(T0.plusHours(2));
        var lines = lines();
        assertThat(lines).hasSize(1);
        var line = lines.get(0);
        assertThat(line.get("environment").asString()).isEqualTo("스테이징");
        assertThat(line.get("environmentId").asString()).isEqualTo(r.env("스테이징").toString());
        assertThat(line.get("result").asString()).isEqualTo("passed");
        assertThat(line.get("by").get("name").asString()).isEqualTo("한도윤");
        assertThat(line.get("stale").asBoolean()).isFalse();
        assertThat(line.get("project").asString()).isEqualTo(r.project);
        assertThat(line.get("targetEnvironmentId").asString()).isEqualTo(r.env("운영").toString());
    }

    @Test void staleIsMarked() throws Exception {
        pass(T0.plusHours(2));
        r.deploy("스테이징", r.commit(r.sha(), T0.plusHours(1), wp), T0.plusHours(3));
        assertThat(lines().get(0).get("stale").asBoolean()).isTrue();
    }

    @Test void noLineWithoutVerification() throws Exception {
        assertThat(lines()).isEmpty();
    }

    @Test void noLineWithoutAProductionEnvironment() throws Exception {
        pass(T0.plusHours(2));
        db.execute("update cicd_environments set hidden = true where id = ?", r.env("운영"));
        assertThat(lines()).isEmpty();
    }

    @Test void theLatestResultWins() throws Exception {
        r.verification(wp, "스테이징", "failed", "실패 사유", T0.plusHours(2), r.addedAt("스테이징", commit));
        pass(T0.plusHours(3));
        var lines = lines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("result").asString()).isEqualTo("passed");
    }
}

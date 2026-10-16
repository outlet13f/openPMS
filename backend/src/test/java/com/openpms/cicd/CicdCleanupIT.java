package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_PIPELINES;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.PROJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Build history cleanup: builds before 00:00 (Asia/Seoul) of a chosen day go; deployments and environments stay. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdCleanupIT {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    CicdFixture f;
    MockHttpSession admin, junho, minji;
    UUID connection;
    int seq;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        junho = f.login(CicdFixture.JUNHO_EMAIL);
        minji = f.login(CicdFixture.MINJI_EMAIL);
        connection = UUID.fromString(f.newConnection(admin, "gitlab", "https://gitlab.example.com", null)[0]);
    }

    private static LocalDate today() {
        return LocalDate.now(SEOUL);
    }

    private static OffsetDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(SEOUL).toOffsetDateTime();
    }

    private UUID projectUuid(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne(PROJECTS.ID);
    }

    private UUID repo(String project) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.ID, id).set(CICD_REPOSITORIES.CONNECTION_ID, connection)
                .set(CICD_REPOSITORIES.EXTERNAL_ID, "x-" + id).set(CICD_REPOSITORIES.FULL_PATH, "g/" + id)
                .set(CICD_REPOSITORIES.WEB_URL, "https://gitlab.example.com/g/" + id).set(CICD_REPOSITORIES.PROJECT_ID, projectUuid(project)).execute();
        return id;
    }

    private void detached(UUID repo) {
        db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.DETACHED_AT, OffsetDateTime.now()).where(CICD_REPOSITORIES.ID.eq(repo)).execute();
    }

    /** finished: null for a build still running (it counts by updatedAt). */
    private void build(UUID repo, OffsetDateTime finished, OffsetDateTime updated) {
        db.insertInto(CICD_PIPELINES).set(CICD_PIPELINES.REPOSITORY_ID, repo).set(CICD_PIPELINES.EXTERNAL_ID, "p" + (++seq))
                .set(CICD_PIPELINES.SHA, String.format("%040x", seq)).set(CICD_PIPELINES.STATUS, finished == null ? "running" : "success")
                .set(CICD_PIPELINES.SOURCE, "gitlab").set(CICD_PIPELINES.FINISHED_AT, finished).set(CICD_PIPELINES.UPDATED_AT, updated).execute();
    }

    private int builds(UUID repo) {
        return db.fetchCount(CICD_PIPELINES, CICD_PIPELINES.REPOSITORY_ID.eq(repo));
    }

    private String cleanupUrl(String project) {
        return "/api/v1/projects/" + project + "/cicd/pipelines/cleanup";
    }

    @Test void countsThenDeletesOlderBuildsOnly() throws Exception {
        var p = f.newProject(admin, "cicd");
        var repo = repo(p);
        var cut = today().minusDays(8);
        build(repo, at(cut.minusDays(7), 10, 0), at(cut.minusDays(7), 10, 0));   // finished long before
        build(repo, at(cut.plusDays(2), 10, 0), at(cut.plusDays(2), 10, 0));    // finished after
        build(repo, null, at(cut.minusDays(3), 9, 0));                          // still "running", last heard of before
        var env = db.insertInto(CICD_ENVIRONMENTS).set(CICD_ENVIRONMENTS.PROJECT_ID, projectUuid(p)).set(CICD_ENVIRONMENTS.NAME, "시험")
                .set(CICD_ENVIRONMENTS.POSITION, 1).returning(CICD_ENVIRONMENTS.ID).fetchOne().getId();
        db.insertInto(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.REPOSITORY_ID, repo).set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, env)
                .set(CICD_DEPLOYMENTS.ENVIRONMENT_NAME, "시험").set(CICD_DEPLOYMENTS.EXTERNAL_ID, "d1").set(CICD_DEPLOYMENTS.STATUS, "success")
                .set(CICD_DEPLOYMENTS.DEPLOYED_AT, at(cut.minusDays(9), 8, 0)).set(CICD_DEPLOYMENTS.SOURCE, "gitlab").execute();

        f.send(admin, get(cleanupUrl(p) + "?before=" + cut), "").andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));
        assertThat(builds(repo)).isEqualTo(3);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("openpms.audit");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            f.send(admin, post(cleanupUrl(p)), "{\"before\":\"" + cut + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(2));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("cicd.pipelines.cleaned by=") && m.contains(" project=" + projectUuid(p) + " ")
                        && m.endsWith(" before=" + cut + " deleted=2"));
        assertThat(builds(repo)).isEqualTo(1);
        assertThat(db.fetchCount(CICD_DEPLOYMENTS, CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo))).isEqualTo(1);
        assertThat(db.fetchCount(CICD_ENVIRONMENTS, CICD_ENVIRONMENTS.ID.eq(env))).isEqualTo(1);
        f.send(admin, get(cleanupUrl(p) + "?before=" + cut), "").andExpect(jsonPath("$.count").value(0));
    }

    @Test void cutoffIsMidnightInSeoul() throws Exception {
        var p = f.newProject(admin, "cicd");
        var repo = repo(p);
        var day = today().minusDays(3);
        build(repo, at(day.minusDays(1), 23, 59), at(day.minusDays(1), 23, 59));   // one minute before: goes
        build(repo, at(day, 0, 0), at(day, 0, 0));                                  // exactly on the cutoff: stays
        f.send(admin, post(cleanupUrl(p)), "{\"before\":\"" + day + "\"}").andExpect(jsonPath("$.deleted").value(1));
        assertThat(builds(repo)).isEqualTo(1);
    }

    @Test void onlyThisProjectsRepositories() throws Exception {
        var p = f.newProject(admin, "cicd");
        var other = f.newProject(admin, "cicd");
        var mine = repo(p);
        var detachedOne = repo(p);
        detached(detachedOne);   // a detached repository of this project is cleaned too
        var theirs = repo(other);
        var old = today().minusDays(30);
        for (var r : new UUID[] {mine, detachedOne, theirs}) build(r, at(old, 12, 0), at(old, 12, 0));
        f.send(admin, get(cleanupUrl(p) + "?before=" + today()), "").andExpect(jsonPath("$.count").value(2));
        f.send(admin, post(cleanupUrl(p)), "{\"before\":\"" + today() + "\"}").andExpect(jsonPath("$.deleted").value(2));
        assertThat(builds(mine)).isZero();
        assertThat(builds(detachedOne)).isZero();
        assertThat(builds(theirs)).isEqualTo(1);
    }

    @Test void needsManage() throws Exception {
        var p = f.newProject(admin, "cicd");   // 박준호 is a member, 최민지 an observer
        var repo = repo(p);
        build(repo, at(today().minusDays(30), 12, 0), at(today().minusDays(30), 12, 0));
        var denied = "저장소를 관리할 권한이 없습니다.";
        f.send(junho, get(cleanupUrl(p) + "?before=" + today()), "").andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(denied));
        f.send(junho, post(cleanupUrl(p)), "{\"before\":\"" + today() + "\"}").andExpect(status().isForbidden());
        f.send(minji, get(cleanupUrl(p) + "?before=" + today()), "").andExpect(status().isForbidden());
        f.send(minji, post(cleanupUrl(p)), "{\"before\":\"" + today() + "\"}").andExpect(status().isForbidden());
        assertThat(builds(repo)).isEqualTo(1);
        var off = f.newProject(admin, "work_packages");
        f.send(admin, get(cleanupUrl(off) + "?before=" + today()), "").andExpect(status().isNotFound());
        f.send(admin, post(cleanupUrl(off)), "{\"before\":\"" + today() + "\"}").andExpect(status().isNotFound());
    }

    @Test void badDates() throws Exception {
        var p = f.newProject(admin, "cicd");
        var repo = repo(p);
        build(repo, at(today().minusDays(30), 12, 0), at(today().minusDays(30), 12, 0));
        var pick = "날짜를 고르세요.";
        var past = "오늘이나 그 앞 날짜를 고르세요.";
        f.send(admin, get(cleanupUrl(p)), "").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].field").value("before"))
                .andExpect(jsonPath("$.errors[0].message").value(pick));
        f.send(admin, get(cleanupUrl(p) + "?before=2026-13-01"), "").andExpect(status().isUnprocessableEntity());
        f.send(admin, get(cleanupUrl(p) + "?before=" + today().plusDays(1)), "").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(past));
        f.send(admin, post(cleanupUrl(p)), "{}").andExpect(status().isUnprocessableEntity());
        f.mvc.perform(post(cleanupUrl(p)).session(admin).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].message").value(pick));
        f.send(admin, post(cleanupUrl(p)), "{\"before\":\"2026-13-01\"}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post(cleanupUrl(p)), "{\"before\":12}").andExpect(status().isUnprocessableEntity());
        f.send(admin, post(cleanupUrl(p)), "{\"before\":\"" + today().plusDays(1) + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value(past));
        assertThat(builds(repo)).isEqualTo(1);
        // today itself is allowed
        f.send(admin, get(cleanupUrl(p) + "?before=" + today()), "").andExpect(status().isOk());
    }

    @Disabled("ApiTokenFilter in Task 8")
    @Test void apiTokenIsRefused() throws Exception {
        db.deleteFrom(com.openpms.db.Tables.SETTINGS).where(com.openpms.db.Tables.SETTINGS.KEY.eq("api.tokens_enabled")).execute();
        var p = f.newProject(admin, "cicd");
        var secret = f.body(f.send(admin, post("/api/v1/me/api-tokens"), "{\"name\":\"정리 시험 토큰\",\"scope\":\"write\",\"expiresInDays\":30}")
                .andExpect(status().isCreated()).andReturn()).get("secret").asString();
        try {
            f.mvc.perform(get(cleanupUrl(p) + "?before=" + today()).header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다."));
        } finally {
            db.deleteFrom(com.openpms.db.Tables.API_TOKENS).where(com.openpms.db.Tables.API_TOKENS.NAME.eq("정리 시험 토큰")).execute();
        }
    }
}

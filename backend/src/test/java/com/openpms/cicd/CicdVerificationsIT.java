package com.openpms.cicd;

import static com.openpms.cicd.ReleaseFixture.card;
import static com.openpms.cicd.ReleaseFixture.numbers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.common.IdempotencyFilter;
import com.openpms.wp.Assignees;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** Verifying, excluding and restoring on the release board: who may, the checks, the activity line and the failure notifications. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdVerificationsIT {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String QA = ReleaseFixture.DOYOON_EMAIL;
    static final UUID MANAGER_ROLE = UUID.fromString("0329397e-d933-5ac1-b27c-95889ba1a893");
    static final String NOT_ON_BOARD = "이 보드에 없는 작업입니다. 이미 대상 환경에 들어갔거나 연결이 바뀌었습니다.";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired Assignees assignees;
    @Autowired IdempotencyFilter idempotency;
    ReleaseFixture r;
    MockHttpSession qa;
    long wp1, wp2;
    UUID c1;
    final List<String> keys = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        r = new ReleaseFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(idempotency).build(), db);
        r.f.addMember(r.admin, r.project, ReleaseFixture.HANEUL, CicdFixture.ROLE_MEMBER);
        qa = r.f.login(QA);
        wp1 = r.wp();
        wp2 = r.wp();
        c1 = r.commit(r.sha(), T0, wp1);
        r.deploy("스테이징", c1, T0.plusHours(1));
        r.deploy("스테이징", r.commit(r.sha(), T0, wp2), T0.plusHours(2));
        assign(wp1, CicdFixture.JUNHO, ReleaseFixture.HANEUL);
    }

    /** Other ITs count the idempotency_keys rows: leave none behind. */
    @AfterEach void forgetKeys() {
        for (var key : keys) db.execute("delete from idempotency_keys where key = ?", key);
    }

    void assign(long wp, UUID... people) {
        var list = new ArrayList<Assignees.Entry>();
        for (var u : people) list.add(new Assignees.Entry(u, null));
        assignees.write(r.wpId(wp), list);
    }

    String base() {
        return "/api/v1/projects/" + r.project + "/releases";
    }

    String body(long wp, String source, String target, String result, String reason) {
        var m = new LinkedHashMap<String, Object>();
        m.put("workPackageId", r.wpId(wp));
        m.put("environmentId", r.env(source));
        m.put("targetEnvironmentId", r.env(target));
        if (result != null) m.put("result", result);
        if (reason != null) m.put("reason", reason);
        return r.f.json.writeValueAsString(m);
    }

    ResultActions verify(MockHttpSession who, long wp, String result, String reason) throws Exception {
        return r.f.send(who, post(base() + "/verifications"), body(wp, "스테이징", "운영", result, reason));
    }

    ResultActions verifyWithKey(MockHttpSession who, long wp, String result, String reason, String key) throws Exception {
        keys.add(key);
        return r.f.send(who, post(base() + "/verifications").header("Idempotency-Key", key), body(wp, "스테이징", "운영", result, reason));
    }

    int count(String sql, Object... args) {
        return db.fetchOne("select count(*) from (" + sql + ") x", args).get(0, Integer.class);
    }

    int verifications(long wp) {
        return count("select 1 from cicd_verifications where work_package_id = ?", r.wpId(wp));
    }

    int notifications(long wp, UUID recipient) {
        return count("select 1 from notifications where work_package_id = ? and recipient_id = ? and reason = 'verification_failed'", r.wpId(wp), recipient);
    }

    JsonNode activities(long wp) throws Exception {
        return r.f.body(r.f.send(r.admin, get("/api/v1/work-packages/" + wp + "/activities"), "").andExpect(status().isOk()).andReturn());
    }

    /** A private sub-project with work packages only, its id; the work package wp's commit is in the parent's 스테이징. */
    String child() throws Exception {
        var child = "cd-" + UUID.randomUUID().toString().substring(0, 8);
        var childId = r.f.body(r.f.send(r.admin, post("/api/v1/projects"),
                "{\"name\":\"하위 " + child + "\",\"identifier\":\"" + child + "\",\"parentId\":\"" + r.projectId + "\"}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        r.f.send(r.admin, patch("/api/v1/projects/" + childId), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        return child;
    }

    UUID memberId(UUID user) {
        return db.fetchOne("select id from members where project_id = ? and user_id = ?", r.projectId, user).get(0, UUID.class);
    }

    @Test void qaPassesAndFailsWithAReason() throws Exception {
        var passed = r.f.body(verify(qa, wp1, "passed", "무시되는 사유").andExpect(status().isCreated()).andReturn());
        assertThat(passed.get("result").asString()).isEqualTo("passed");
        assertThat(passed.get("reason").asString()).isEmpty();
        assertThat(passed.at("/by/name").asString()).isEqualTo("한도윤");
        assertThat(passed.get("environment").asString()).isEqualTo("스테이징");
        var failed = r.f.body(verify(qa, wp2, "failed", "  로그인 실패 ").andExpect(status().isCreated()).andReturn());
        assertThat(failed.get("result").asString()).isEqualTo("failed");
        assertThat(failed.get("reason").asString()).isEqualTo("로그인 실패");

        assertThat(verifications(wp1)).isEqualTo(1);
        assertThat(verifications(wp2)).isEqualTo(1);
        var row = db.fetchOne("select basis_at, verified_by, reason from cicd_verifications where work_package_id = ?", r.wpId(wp1));
        assertThat(row.get(0, OffsetDateTime.class).toInstant()).isEqualTo(r.addedAt("스테이징", c1).toInstant());
        assertThat(row.get(1, UUID.class)).isEqualTo(CicdFixture.DOYOON);
        assertThat(row.get(2, String.class)).isEmpty();
        var board = r.board(QA, "");
        assertThat(card(board, wp1).get("column").asString()).isEqualTo("passed");
        assertThat(card(board, wp2).get("column").asString()).isEqualTo("failed");
        assertThat(card(board, wp2).at("/last/reason").asString()).isEqualTo("로그인 실패");
    }

    @Test void failingNeedsAReason() throws Exception {
        for (var reason : new String[] {null, "", "   \n "})
            verify(qa, wp2, "failed", reason).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(jsonPath("$.errors[0].message").value("실패 사유를 적으세요."));
        verify(qa, wp2, "failed", "가".repeat(2001)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(jsonPath("$.errors[0].message").value("실패 사유는 2,000자까지 적을 수 있습니다."));
        // counted in code points, after trimming
        verify(qa, wp2, "failed", " " + "😀".repeat(2000) + " ").andExpect(status().isCreated());
        verify(qa, wp2, "failed", "가".repeat(2000)).andExpect(status().isCreated());
        for (var result : new String[] {"maybe", null})
            verify(qa, wp2, result, "x").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("result"))
                    .andExpect(jsonPath("$.errors[0].message").value("결과는 통과(passed)나 실패(failed)여야 합니다."));
        assertThat(verifications(wp2)).isEqualTo(2);
    }

    @Test void whoMayVerify() throws Exception {
        r.f.send(r.admin, patch("/api/v1/members/" + memberId(CicdFixture.JUNHO)), "{\"roleIds\":[\"" + CicdFixture.ROLE_DEV_LEAD + "\"]}")
                .andExpect(status().isOk());
        verify(r.f.login(CicdFixture.JUNHO_EMAIL), wp1, "passed", null).andExpect(status().isCreated());
        var haneul = r.f.login(ReleaseFixture.HANEUL_EMAIL);
        verify(haneul, wp1, "passed", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("릴리스를 검증할 권한이 없습니다."));
        r.f.send(haneul, post(base() + "/exclusions"), body(wp1, "스테이징", "운영", null, null)).andExpect(status().isForbidden());
        verify(r.f.login(CicdFixture.MINJI_EMAIL), wp1, "passed", null).andExpect(status().isForbidden());
        verify(r.f.login(ReleaseFixture.ALEX_EMAIL), wp1, "passed", null).andExpect(status().isNotFound());
        r.f.send(r.admin, patch("/api/v1/members/" + memberId(ReleaseFixture.HANEUL)), "{\"roleIds\":[\"" + MANAGER_ROLE + "\"]}")
                .andExpect(status().isOk());
        verify(haneul, wp1, "passed", null).andExpect(status().isCreated());
        assertThat(verifications(wp1)).isEqualTo(2);

        var project = "/api/v1/projects/" + r.project;
        assertThat(r.f.body(r.f.send(qa, get(project), "").andReturn()).get("myPermissions").valueStream().map(JsonNode::asString))
                .contains("release.verify");
        r.f.send(r.admin, patch(project), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        verify(qa, wp1, "passed", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("프로젝트를 찾을 수 없습니다."));
        assertThat(r.f.body(r.f.send(qa, get(project), "").andReturn()).get("myPermissions").valueStream().map(JsonNode::asString))
                .doesNotContain("release.verify");
    }

    @Test void aWorkPackageNotOnTheBoardIs422() throws Exception {
        long wp3 = r.wp(), wp4 = r.wp();
        var c3 = r.commit(r.sha(), T0, wp3);
        r.deploy("스테이징", c3, T0.plusMinutes(10));
        r.deploy("운영", c3, T0.plusMinutes(30));   // before wp1 and wp2 came into 스테이징: they stay on the board
        verify(qa, wp3, "passed", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.title").value(NOT_ON_BOARD));
        r.deploy("개발", r.commit(r.sha(), T0, wp4), T0.plusHours(1));   // on the 개발 → 스테이징 board only
        verify(qa, wp4, "passed", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.title").value(NOT_ON_BOARD));
        r.f.send(qa, post(base() + "/exclusions"), body(wp4, "스테이징", "운영", null, null)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value(NOT_ON_BOARD));
        r.f.send(qa, post(base() + "/verifications"), body(wp4, "개발", "스테이징", "passed", null)).andExpect(status().isCreated());
        assertThat(verifications(wp3)).isZero();
        assertThat(verifications(wp4)).isEqualTo(1);
    }

    @Test void anInvisibleWorkPackageIs404() throws Exception {
        long hidden = r.wp(child());
        r.deploy("스테이징", r.commit(r.sha(), T0, hidden), T0.plusHours(1));
        verify(qa, hidden, "failed", "보이지 않음").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("작업을 찾을 수 없습니다."));
        r.f.send(qa, post(base() + "/exclusions"), body(hidden, "스테이징", "운영", null, null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("작업을 찾을 수 없습니다."));
        var unknown = "{\"workPackageId\":\"" + UUID.randomUUID() + "\",\"environmentId\":\"" + r.env("스테이징") + "\",\"targetEnvironmentId\":\""
                + r.env("운영") + "\",\"result\":\"passed\"}";
        r.f.send(qa, post(base() + "/verifications"), unknown).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("작업을 찾을 수 없습니다."));
        verify(r.admin, hidden, "passed", null).andExpect(status().isCreated());   // on the board for someone who sees it
    }

    @Test void failureNotifiesAssigneesButNotTheVerifier() throws Exception {
        assign(wp2, CicdFixture.JUNHO, ReleaseFixture.HANEUL, CicdFixture.DOYOON);
        var made = r.f.body(verify(qa, wp2, "failed", "로그인 실패").andExpect(status().isCreated()).andReturn());
        assertThat(notifications(wp2, CicdFixture.JUNHO)).isEqualTo(1);
        assertThat(notifications(wp2, ReleaseFixture.HANEUL)).isEqualTo(1);
        assertThat(notifications(wp2, CicdFixture.DOYOON)).isZero();
        var journal = db.fetchOne("select id from journals where work_package_id = ? order by version desc limit 1", r.wpId(wp2)).get(0, UUID.class);
        assertThat(count("select 1 from notifications where work_package_id = ? and reason = 'verification_failed' and journal_id = ? and actor_id = ? and project_id = ?",
                r.wpId(wp2), journal, CicdFixture.DOYOON, r.projectId)).isEqualTo(2);
        assertThat(made.get("id").asString()).isNotEmpty();

        var groups = r.f.body(r.f.send(r.f.login(CicdFixture.JUNHO_EMAIL), get("/api/v1/notifications?reasons=verification_failed"), "")
                .andExpect(status().isOk()).andReturn());
        var summaries = new ArrayList<String>();
        for (var g : groups)
            if (g.at("/workPackage/number").asLong() == wp2) g.get("items").forEach(i -> summaries.add(i.get("summary").asString()));
        assertThat(summaries).containsExactly("한도윤님이 스테이징 검증을 실패로 남겼습니다");

        verify(qa, wp1, "passed", null).andExpect(status().isCreated());
        assertThat(count("select 1 from notifications where work_package_id = ?", r.wpId(wp1))).isZero();
    }

    @Test void failureNotifiesOnlyAssigneesWhoCanSeeTheWorkPackage() throws Exception {
        var child = child();
        r.f.addMember(r.admin, child, CicdFixture.JUNHO, CicdFixture.ROLE_MEMBER);
        long wp7 = r.wp(child);
        r.deploy("스테이징", r.commit(r.sha(), T0, wp7), T0.plusHours(1));
        assign(wp7, CicdFixture.JUNHO, CicdFixture.MINJI);   // 최민지 is not a member of the sub-project
        verify(r.admin, wp7, "failed", "하위 프로젝트 실패").andExpect(status().isCreated());
        assertThat(notifications(wp7, CicdFixture.JUNHO)).isEqualTo(1);
        assertThat(notifications(wp7, CicdFixture.MINJI)).isZero();
        assertThat(count("select 1 from notifications where work_package_id = ?", r.wpId(wp7))).isEqualTo(1);
    }

    @Test void activityShowsTheVerification() throws Exception {
        var name = "검증 웹훅 " + UUID.randomUUID();
        r.f.send(r.admin, post("/api/v1/admin/webhooks"), "{\"name\":\"" + name + "\",\"url\":\"http://127.0.0.1:9/hook\",\"events\":[\"work_package.created\","
                + "\"work_package.updated\",\"work_package.deleted\",\"work_package.commented\"],\"projectIds\":[\"" + r.projectId + "\"]}")
                .andExpect(status().isCreated());
        try {
            int deliveries = count("select 1 from webhook_deliveries");
            verify(qa, wp2, "failed", "로그인 실패").andExpect(status().isCreated());
            var list = activities(wp2);
            var last = list.get(list.size() - 1);
            assertThat(last.get("cause").get("kind").asString()).isEqualTo("verification_failed");
            assertThat(last.get("cause").get("environment").asString()).isEqualTo("스테이징");
            assertThat(last.get("cause").get("reason").asString()).isEqualTo("로그인 실패");
            assertThat(last.get("details")).isEmpty();
            assertThat(last.at("/user/name").asString()).isEqualTo("한도윤");

            verify(qa, wp1, "passed", null).andExpect(status().isCreated());
            list = activities(wp1);
            last = list.get(list.size() - 1);
            assertThat(last.get("cause").get("kind").asString()).isEqualTo("verification_passed");
            assertThat(last.get("cause").has("reason")).isFalse();
            assertThat(last.get("details")).isEmpty();
            assertThat(count("select 1 from webhook_deliveries")).isEqualTo(deliveries);
            // the webhook itself works: a new work package of the project is queued
            r.wp();
            assertThat(count("select 1 from webhook_deliveries")).isGreaterThan(deliveries);
        } finally {
            db.execute("delete from webhooks where name = ?", name);
        }
    }

    @Test void excludeAndRestore() throws Exception {
        var made = r.f.body(r.f.send(qa, post(base() + "/exclusions"), body(wp1, "스테이징", "운영", null, null)).andExpect(status().isCreated()).andReturn());
        assertThat(made.get("number").asLong()).isEqualTo(wp1);
        assertThat(made.at("/by/name").asString()).isEqualTo("한도윤");
        var board = r.board(QA, "");
        assertThat(numbers(board)).containsExactly(wp2);
        assertThat(board.get("excluded").valueStream().map(e -> e.get("number").asLong()).toList()).containsExactly(wp1);
        assertThat(board.at("/excluded/0/by/name").asString()).isEqualTo("한도윤");
        var row = db.fetchOne("select added_before from cicd_board_exclusions where work_package_id = ?", r.wpId(wp1));
        assertThat(row.get(0, OffsetDateTime.class).toInstant()).isEqualTo(r.addedAt("스테이징", c1).toInstant());

        r.f.send(qa, post(base() + "/exclusions"), body(wp1, "스테이징", "운영", null, null)).andExpect(status().isCreated());
        assertThat(count("select 1 from cicd_board_exclusions where work_package_id = ?", r.wpId(wp1))).isEqualTo(1);

        var restore = base() + "/exclusions?workPackageId=" + r.wpId(wp1) + "&environmentId=" + r.env("스테이징") + "&targetEnvironmentId=" + r.env("운영");
        r.f.send(r.f.login(ReleaseFixture.HANEUL_EMAIL), delete(restore), "").andExpect(status().isForbidden());
        r.f.send(qa, delete(restore), "").andExpect(status().isNoContent());
        assertThat(numbers(r.board(QA, ""))).containsExactly(wp1, wp2);
        r.f.send(qa, delete(restore), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("뺀 작업을 찾을 수 없습니다."));
        // another project's environments do not reach this project's exclusions
        r.exclusion(wp1, "스테이징", "운영", r.addedAt("스테이징", c1));
        var other = r.f.newProject(r.admin, "work_packages", "cicd");
        r.f.send(r.admin, delete("/api/v1/projects/" + other + "/releases/exclusions?workPackageId=" + r.wpId(wp1) + "&environmentId=" + r.env("스테이징")
                + "&targetEnvironmentId=" + r.env("운영")), "").andExpect(status().isNotFound());
        assertThat(count("select 1 from cicd_board_exclusions where work_package_id = ?", r.wpId(wp1))).isEqualTo(1);
    }

    @Test void sameIdempotencyKeyWritesOneRow() throws Exception {
        var key = UUID.randomUUID().toString();
        var first = r.f.body(verifyWithKey(qa, wp1, "failed", "같은 요청", key).andExpect(status().isCreated()).andReturn());
        var second = r.f.body(verifyWithKey(qa, wp1, "failed", "같은 요청", key).andExpect(status().isCreated()).andReturn());
        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(verifications(wp1)).isEqualTo(1);
        assertThat(notifications(wp1, CicdFixture.JUNHO)).isEqualTo(1);
        assertThat(notifications(wp1, ReleaseFixture.HANEUL)).isEqualTo(1);
    }

    @Test void apiTokensAreClosedOnReleases() throws Exception {
        db.deleteFrom(com.openpms.db.Tables.SETTINGS).where(com.openpms.db.Tables.SETTINGS.KEY.eq("api.tokens_enabled")).execute();
        var secret = r.f.body(r.f.send(r.admin, post("/api/v1/me/api-tokens"), "{\"name\":\"릴리스 시험 토큰\",\"scope\":\"write\",\"expiresInDays\":30}")
                .andExpect(status().isCreated()).andReturn()).get("secret").asString();
        try {
            var refused = "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.";
            r.f.mvc.perform(get(base() + "/board").header("Authorization", "Bearer " + secret)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value(refused));
            r.f.mvc.perform(post(base() + "/verifications").header("Authorization", "Bearer " + secret).contentType("application/json")
                    .content(body(wp1, "스테이징", "운영", "passed", null))).andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            r.f.mvc.perform(get(base() + "/records").header("Authorization", "Bearer " + secret)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value(refused));
            r.f.mvc.perform(get(new java.net.URI(base().replace("/releases", "/%72eleases") + "/board")).header("Authorization", "Bearer " + secret))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            r.f.mvc.perform(get("/api/v1/projects/" + r.project + "/cicd/repositories").header("Authorization", "Bearer " + secret)).andExpect(status().isOk());
            assertThat(verifications(wp1)).isZero();
        } finally {
            db.deleteFrom(com.openpms.db.Tables.API_TOKENS).where(com.openpms.db.Tables.API_TOKENS.NAME.eq("릴리스 시험 토큰")).execute();
        }
    }

    @Test void theFailureReasonIsNeverLogged() throws Exception {
        var marker = "사유-표식-8d1f";
        long wp3 = r.wp();
        var c3 = r.commit(r.sha(), T0, wp3);
        r.deploy("스테이징", c3, T0.plusMinutes(10));
        r.deploy("운영", c3, T0.plusMinutes(30));   // before wp1 and wp2 came into 스테이징: they stay on the board
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var key = UUID.randomUUID().toString();
            verifyWithKey(qa, wp2, "failed", marker, key).andExpect(status().isCreated());
            verify(qa, wp3, "failed", marker).andExpect(status().isUnprocessableContent());
            verify(qa, wp2, "failed", marker + "가".repeat(2000)).andExpect(status().isUnprocessableContent());
            verifyWithKey(qa, wp2, "failed", marker, key).andExpect(status().isCreated());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(verifications(wp2)).isEqualTo(1);
        var lines = new ArrayList<String>();
        for (var e : appender.list) {
            lines.add(String.valueOf(e.getFormattedMessage()));
            for (var t = e.getThrowableProxy(); t != null; t = t.getCause()) lines.add(String.valueOf(t.getMessage()));
        }
        assertThat(lines).noneMatch(m -> m.contains(marker));
    }
}

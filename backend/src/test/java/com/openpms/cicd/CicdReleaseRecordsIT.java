package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** The release record list and detail: paging, what each viewer sees of the items, warnings, and who may look. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdReleaseRecordsIT {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-15T01:00:00Z");
    static final String QA = ReleaseFixture.DOYOON_EMAIL;
    static final String NOT_FOUND = "릴리스 기록을 찾을 수 없습니다.";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    ReleaseFixture r;

    @BeforeEach void setUp() throws Exception {
        r = new ReleaseFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(), db);
    }

    UUID release(OffsetDateTime at, boolean partial, boolean contentFailed) {
        return r.release("운영", r.deployment("운영", r.sha(), "success", "compared", at), at, partial, contentFailed, "스테이징");
    }

    JsonNode records(String email, String query) throws Exception {
        return r.f.body(r.recordsRequest(email, query).andExpect(status().isOk()).andReturn());
    }

    JsonNode detail(String email, UUID id) throws Exception {
        return r.f.body(r.recordRequest(r.project, email, id).andExpect(status().isOk()).andReturn());
    }

    @Test void listIsPagedNewestFirst() throws Exception {
        var oldest = release(T0, false, false);
        var middle = release(T0.plusHours(1), false, false);
        var newest = release(T0.plusHours(2), false, false);
        var page = records(QA, "page=1&pageSize=2");
        assertThat(page.get("total").asInt()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("pageSize").asInt()).isEqualTo(2);
        assertThat(page.get("items").valueStream().map(i -> i.get("id").asString()).toList())
                .containsExactly(newest.toString(), middle.toString());
        var second = records(QA, "page=2&pageSize=2");
        assertThat(second.get("items").valueStream().map(i -> i.get("id").asString()).toList()).containsExactly(oldest.toString());
        assertThat(records(QA, "pageSize=500").get("pageSize").asInt()).isEqualTo(100);
        assertThat(records(QA, "").get("pageSize").asInt()).isEqualTo(20);
        assertThat(records(QA, "page=0&pageSize=0").get("page").asInt()).isEqualTo(1);
        var row = page.get("items").get(0);
        assertThat(row.at("/environment/name").asString()).isEqualTo("운영");
        assertThat(row.get("sourceEnvironment").asString()).isEqualTo("스테이징");
        assertThat(row.get("deploymentUrl").asString()).startsWith(ReleaseFixture.HOST);
        assertThat(row.get("repository").asString()).isEqualTo("g/" + r.repo);
    }

    @Test void countsHideInvisibleWorkPackages() throws Exception {
        var child = "cd-" + UUID.randomUUID().toString().substring(0, 8);
        var childId = r.f.body(r.f.send(r.admin, post("/api/v1/projects"),
                "{\"name\":\"하위 " + child + "\",\"identifier\":\"" + child + "\",\"parentId\":\"" + r.projectId + "\"}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        r.f.send(r.admin, patch("/api/v1/projects/" + childId), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        long shown = r.wp();
        long hidden = r.wp(child);
        long deleted = r.wp();
        var id = release(T0, false, false);
        r.releaseItem(id, shown, null, null);
        r.releaseItem(id, hidden, null, null);
        r.releaseItem(id, deleted, null, null);
        db.execute("delete from work_packages where number = ?", deleted);

        var qa = records(QA, "").get("items").get(0);
        assertThat(qa.get("itemCount").asInt()).isEqualTo(1);
        assertThat(qa.get("hiddenCount").asInt()).isEqualTo(2);
        var items = detail(QA, id).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("number").asLong()).isEqualTo(shown);
        assertThat(items.toString()).doesNotContain("" + hidden + ",\"subject\"");
        assertThat(detail(QA, id).get("release").get("hiddenCount").asInt()).isEqualTo(2);

        var admin = records(CicdFixture.ADMIN, "").get("items").get(0);
        assertThat(admin.get("itemCount").asInt()).isEqualTo(2);
        assertThat(admin.get("hiddenCount").asInt()).isEqualTo(1);
        assertThat(detail(CicdFixture.ADMIN, id).get("items")).hasSize(2);
    }

    @Test void warningsAreCountedAndShown() throws Exception {
        long a = r.wp(), b = r.wp(), c = r.wp(), d = r.wp();
        var passed = r.verification(d, "스테이징", "passed", "", T0, T0);
        var failed = r.verification(c, "스테이징", "failed", "결제 오류", T0, T0);
        var id = release(T0.plusHours(1), false, false);
        r.releaseItem(id, a, null, "unverified");
        r.releaseItem(id, b, null, "stale");
        r.releaseItem(id, c, failed, "failed");
        r.releaseItem(id, d, passed, null);

        var row = records(QA, "").get("items").get(0);
        assertThat(row.get("itemCount").asInt()).isEqualTo(4);
        assertThat(row.get("warningCount").asInt()).isEqualTo(3);
        var items = detail(QA, id).get("items");
        assertThat(items).hasSize(4);
        JsonNode item = null;
        for (var i : items) if (i.get("number").asLong() == c) item = i;
        assertThat(item.get("warning").asString()).isEqualTo("failed");
        assertThat(item.at("/verification/result").asString()).isEqualTo("failed");
        assertThat(item.at("/verification/reason").asString()).isEqualTo("결제 오류");
        assertThat(item.at("/verification/by/name").asString()).isEqualTo("한도윤");
        assertThat(item.at("/verification/environment").asString()).isEqualTo("스테이징");
        for (var i : items) if (i.get("number").asLong() == d) {
            assertThat(i.get("warning").isNull()).isTrue();
            assertThat(i.at("/verification/result").asString()).isEqualTo("passed");
        } else if (i.get("number").asLong() == a) assertThat(i.get("verification").isNull()).isTrue();
    }

    @Test void contentFailedHasNoItems() throws Exception {
        var id = release(T0, false, true);
        var row = records(QA, "").get("items").get(0);
        assertThat(row.get("contentFailed").asBoolean()).isTrue();
        assertThat(row.get("itemCount").asInt()).isZero();
        assertThat(row.get("sourceEnvironment").asString()).isEqualTo("스테이징");
        var d = detail(QA, id);
        assertThat(d.get("items")).isEmpty();
        assertThat(d.at("/release/contentFailed").asBoolean()).isTrue();
        var partial = release(T0.plusHours(1), true, false);
        assertThat(detail(QA, partial).at("/release/partial").asBoolean()).isTrue();
    }

    @Test void whoSeesRecords() throws Exception {
        var id = release(T0, false, false);
        r.recordsRequest(CicdFixture.MINJI_EMAIL, "").andExpect(status().isOk());
        r.recordRequest(r.project, CicdFixture.MINJI_EMAIL, id).andExpect(status().isOk());
        // 하늘 is no member of the private project
        r.recordsRequest(ReleaseFixture.HANEUL_EMAIL, "").andExpect(status().isNotFound());
        r.recordRequest(r.project, ReleaseFixture.HANEUL_EMAIL, id).andExpect(status().isNotFound());
        // another project's record
        var other = new ReleaseFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(), db);
        var foreign = other.release("운영", other.deployment("운영", other.sha(), "success", "compared", T0), T0, false, false, null);
        r.recordRequest(r.project, QA, foreign).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        r.recordRequest(r.project, QA, UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value(NOT_FOUND));
        // module off
        r.f.send(r.admin, patch("/api/v1/projects/" + r.project), "{\"enabledModules\":[\"work_packages\"]}").andExpect(status().isOk());
        r.recordsRequest(QA, "").andExpect(status().isNotFound());
        r.recordRequest(r.project, QA, id).andExpect(status().isNotFound());
    }

    @Test void detachedRepositoryRecordsStay() throws Exception {
        release(T0, false, false);
        db.execute("update cicd_repositories set detached_at = now() where id = ?", r.repo);
        var items = records(QA, "").get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("repository").asString()).isEqualTo("g/" + r.repo);
    }
}

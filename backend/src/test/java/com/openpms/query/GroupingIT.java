package com.openpms.query;

import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.STATUSES;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import com.openpms.common.SettingsRepo;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S06 그룹화와 합계: groups cover every matching item in group order, sums add up, % complete totals as the progress settings say. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class GroupingIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    SettingsRepo settings;

    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    JsonNode query(String spec, int pageSize) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        var body = "{\"projectId\":\"web-platform\",\"page\":1,\"pageSize\":" + pageSize + ",\"spec\":" + spec + "}";
        var res = mvc.perform(post("/api/v1/work-packages/query").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    static String spec(String groupBy, boolean sums, boolean hierarchy) {
        return "{\"filters\":[],\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"columns\":[\"subject\",\"work\",\"remainingWork\",\"doneRatio\"],"
                + "\"groupBy\":" + (groupBy == null ? "null" : "\"" + groupBy + "\"") + ",\"sums\":" + sums + ",\"hierarchy\":" + hierarchy + ",\"includeSubprojects\":false}";
    }

    static BigDecimal dec(JsonNode n) {
        return n == null || n.isNull() ? BigDecimal.ZERO : n.decimalValue();
    }

    @Test
    void groupsCoverEveryMatchAndRowsComeInGroupOrder() throws Exception {
        var r = query(spec("status", true, true), 1000);
        var groups = r.get("groups");
        assertThat(groups.size()).isGreaterThan(1);
        long counted = 0;
        var order = new ArrayList<String>();
        for (var g : groups) {
            counted += g.get("count").asLong();
            order.add(g.get("key").asString());
            assertThat(g.get("label").asString()).isNotBlank();
        }
        assertThat(counted).isEqualTo(r.get("total").asLong());
        // every row carries its group, the group of its status, and groups do not interleave; a grouped list is flat
        var seen = new LinkedHashSet<String>();
        String last = null;
        for (var row : r.get("rows")) {
            var key = row.get("groupKey").asString();
            assertThat(key).isEqualTo(row.get("wp").get("status").get("id").asString());
            assertThat(row.get("depth").asInt()).isZero();
            if (!key.equals(last)) {
                assertThat(seen.add(key)).as("group %s appears once", key).isTrue();
                last = key;
            }
        }
        assertThat(new ArrayList<>(seen)).isEqualTo(order);
    }

    UUID project() {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq("web-platform")).fetchOne(PROJECTS.ID);
    }

    /** Items whose status counts toward totals (취소 in the seed does not). */
    Condition counted() {
        return WORK_PACKAGES.PROJECT_ID.eq(project()).and(WORK_PACKAGES.STATUS_ID.in(DSL.select(STATUSES.ID).from(STATUSES).where(STATUSES.EXCLUDED_FROM_TOTALS.isFalse())));
    }

    @Test
    void sumsAddUpAndPercentCompleteIsWeightedByWork() throws Exception {
        var r = query(spec("assignee", true, false), 5);
        var project = project();
        var work = db.select(DSL.sum(WORK_PACKAGES.WORK_HOURS)).from(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(project)).fetchOne().value1();
        var remaining = db.select(DSL.sum(WORK_PACKAGES.REMAINING_HOURS)).from(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(project)).fetchOne().value1();
        var sums = r.get("sums");
        assertThat(dec(sums.get("work"))).isEqualByComparingTo(work == null ? BigDecimal.ZERO : work);
        assertThat(dec(sums.get("remainingWork"))).isEqualByComparingTo(remaining == null ? BigDecimal.ZERO : remaining);
        // % complete leaves out the statuses excluded from totals, like an item's ∑
        var countedWork = db.select(DSL.sum(WORK_PACKAGES.WORK_HOURS)).from(WORK_PACKAGES).where(counted()).fetchOne().value1();
        var countedRemaining = db.select(DSL.sum(WORK_PACKAGES.REMAINING_HOURS)).from(WORK_PACKAGES).where(counted()).fetchOne().value1();
        if (countedWork != null && countedWork.signum() > 0) {
            var expected = countedWork.subtract(countedRemaining == null ? BigDecimal.ZERO : countedRemaining).multiply(BigDecimal.valueOf(100))
                    .divide(countedWork, 0, RoundingMode.HALF_UP);
            assertThat(dec(sums.get("doneRatio"))).isEqualByComparingTo(expected);
        }
        // the group sums are over every item of the group, not just this page of 5
        var byGroups = BigDecimal.ZERO;
        for (var g : r.get("groups")) byGroups = byGroups.add(dec(g.get("sums").get("work")));
        assertThat(byGroups).isEqualByComparingTo(dec(sums.get("work")));
        assertThat(r.get("rows").size()).isEqualTo(5);
        // the "no assignee" group comes last
        var groups = r.get("groups");
        for (int i = 0; i < groups.size() - 1; i++) assertThat(groups.get(i).get("key").asString()).isNotEmpty();
    }

    @Test
    void withSimpleAverageTotalsPercentCompleteIsTheAverageOfTheItems() throws Exception {
        settings.put("progress.totals", "\"simple\"", null);
        try {
            var sums = query(spec(null, true, false), 5).get("sums");
            var avg = db.select(DSL.avg(WORK_PACKAGES.DONE_RATIO)).from(WORK_PACKAGES).where(counted(), WORK_PACKAGES.DONE_RATIO.isNotNull()).fetchOne().value1();
            assertThat(dec(sums.get("doneRatio"))).isEqualByComparingTo(avg.setScale(0, RoundingMode.HALF_UP));
        } finally {
            settings.put("progress.totals", "\"weighted\"", null);
        }
    }

    @Test
    void withoutGroupingOrSumsNothingExtraComesBack() throws Exception {
        var r = query(spec(null, false, false), 10);
        assertThat(r.get("groups").isNull()).isTrue();
        assertThat(r.get("sums").isNull()).isTrue();
        assertThat(r.get("rows").get(0).get("groupKey").isNull()).isTrue();
        // not a groupable column: ignored, not an error
        var subject = query(spec("subject", false, false), 10);
        assertThat(subject.get("groups").isNull()).isTrue();
    }
}

package com.openpms.time;

import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.TIME_ACTIVITIES;
import static com.openpms.db.Tables.TIME_ENTRIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.db.tables.records.TimeEntriesRecord;
import java.math.BigDecimal;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 시간 기록: the weekly sheet, a work package's entries, who may log and see what, the 24-hour day, the report and the export. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class TimeEntriesIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;

    MockMvc mvc;
    /** the dev data's entries: put back afterwards */
    Result<TimeEntriesRecord> saved;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        saved = db.selectFrom(TIME_ENTRIES).fetch();
    }

    @AfterEach
    void cleanUp() {
        db.deleteFrom(TIME_ENTRIES).execute();
        saved.forEach(r -> r.changed(true));
        db.batchInsert(saved).execute();
        db.deleteFrom(TIME_ACTIVITIES).where(TIME_ACTIVITIES.NAME.eq("고객 지원")).execute();
        db.update(TIME_ACTIVITIES).set(TIME_ACTIVITIES.IS_DEFAULT, TIME_ACTIVITIES.NAME.eq("개발")).execute();
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    BigDecimal cell(JsonNode week, long number, String date) {
        for (var row : week.get("rows"))
            if (row.get("workPackage").get("number").asLong() == number)
                for (var c : row.get("cells")) if (c.get("date").asString().equals(date)) return c.get("hours").decimalValue();
        return BigDecimal.ZERO;
    }

    @Test
    void theWeeklySheetSetsMyHoursCellByCellAllOrNothing() throws Exception {
        var me = login("seoyeon.kim@openpms.local");
        // any day of the week opens the week (it starts on the day 관리 · 날짜 형식 says)
        var week = body(send(me, get("/api/v1/time-entries/week").param("start", "2026-10-07"), "").andExpect(status().isOk()));
        var start = week.get("start").asString();
        assertThat(week.get("days").size()).isEqualTo(7);
        assertThat(week.get("hoursPerDay").asInt()).isPositive();
        assertThat(cell(week, 3, "2026-10-05")).isEqualByComparingTo("1.5");
        assertThat(cell(week, 12, "2026-10-07")).isEqualByComparingTo("6");

        // a new cell, a changed cell, a cleared cell
        week = body(send(me, put("/api/v1/time-entries/week"), "{\"start\":\"" + start + "\",\"cells\":["
                + "{\"workPackageNumber\":3,\"date\":\"2026-10-07\",\"hours\":2.5},"
                + "{\"workPackageNumber\":3,\"date\":\"2026-10-05\",\"hours\":2},"
                + "{\"workPackageNumber\":12,\"date\":\"2026-10-07\",\"hours\":0}]}").andExpect(status().isOk()));
        assertThat(cell(week, 3, "2026-10-07")).isEqualByComparingTo("2.5");
        assertThat(cell(week, 3, "2026-10-05")).isEqualByComparingTo("2");
        assertThat(cell(week, 12, "2026-10-07")).isEqualByComparingTo("0");
        var made = db.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.HOURS.eq(new BigDecimal("2.50"))).fetchOne();
        assertThat(db.select(TIME_ACTIVITIES.NAME).from(TIME_ACTIVITIES).where(TIME_ACTIVITIES.ID.eq(made.getActivityId())).fetchOne(TIME_ACTIVITIES.NAME))
                .as("the default activity").isEqualTo("개발");

        // a cell holding two entries cannot be set from the sheet; the whole save is refused and nothing changes
        send(me, post("/api/v1/time-entries"), "{\"workPackageNumber\":1,\"spentOn\":\"2026-10-08\",\"hours\":1,\"comment\":\"둘째 건\"}").andExpect(status().isCreated());
        send(me, put("/api/v1/time-entries/week"), "{\"start\":\"" + start + "\",\"cells\":["
                + "{\"workPackageNumber\":3,\"date\":\"2026-10-09\",\"hours\":1},"
                + "{\"workPackageNumber\":1,\"date\":\"2026-10-08\",\"hours\":5}]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("#1 2026-10-08"));
        week = body(send(me, get("/api/v1/time-entries/week").param("start", start), ""));
        assertThat(cell(week, 3, "2026-10-09")).isEqualByComparingTo("0");
        assertThat(cell(week, 1, "2026-10-08")).isEqualByComparingTo("3");
        // a date outside the week, more than 24 hours a day
        send(me, put("/api/v1/time-entries/week"), "{\"start\":\"" + start + "\",\"cells\":[{\"workPackageNumber\":3,\"date\":\"2026-10-20\",\"hours\":1}]}")
                .andExpect(status().isUnprocessableContent());
        send(me, put("/api/v1/time-entries/week"), "{\"start\":\"" + start + "\",\"cells\":[{\"workPackageNumber\":2,\"date\":\"2026-10-06\",\"hours\":21}]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("2026-10-06"));
        send(me, post("/api/v1/time-entries"), "{\"workPackageNumber\":2,\"spentOn\":\"2026-10-06\",\"hours\":20.5}").andExpect(status().isUnprocessableContent());
        send(me, post("/api/v1/time-entries"), "{\"workPackageNumber\":2,\"spentOn\":\"2026-10-06\",\"hours\":1.234}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void whoMayLogSeeAndChangeTime() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var member = login("haneul.lee@openpms.local");   // 멤버 in 웹 플랫폼: logs own time, sees everyone's
        var viewer = login("minji.choi@openpms.local");   // 참관자: no time permissions
        var mine = body(send(member, post("/api/v1/time-entries"), "{\"workPackageNumber\":9,\"spentOn\":\"2026-10-09\",\"hours\":1.5,\"comment\":\"리뷰 반영\"}")
                .andExpect(status().isCreated()));
        assertThat(mine.get("canEdit").asBoolean()).isTrue();
        assertThat(mine.get("activity").get("name").asString()).isEqualTo("개발");

        // the work package's time: everyone's, for whoever may see time there
        var onNine = body(send(member, get("/api/v1/time-entries").param("workPackage", "9"), ""));
        assertThat(onNine.size()).isEqualTo(3);
        var seoyeons = db.select(TIME_ENTRIES.ID).from(TIME_ENTRIES).join(com.openpms.db.Tables.USERS).on(com.openpms.db.Tables.USERS.ID.eq(TIME_ENTRIES.USER_ID))
                .where(com.openpms.db.Tables.USERS.EMAIL.eq("seoyeon.kim@openpms.local")).limit(1).fetchOne(TIME_ENTRIES.ID);
        send(member, patch("/api/v1/time-entries/" + seoyeons), "{\"hours\":1}").andExpect(status().isForbidden());
        send(member, post("/api/v1/time-entries"), "{\"workPackageNumber\":9,\"spentOn\":\"2026-10-09\",\"hours\":1,\"userId\":\""
                + body(send(admin, get("/api/v1/auth/me"), "")).get("id").asString() + "\"}").andExpect(status().isForbidden());
        // the manager (here the administrator) changes anyone's
        send(admin, patch("/api/v1/time-entries/" + mine.get("id").asString()), "{\"hours\":2,\"comment\":\"관리자가 고침\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(2.0));

        // a viewer logs nothing and sees nothing
        send(viewer, post("/api/v1/time-entries"), "{\"workPackageNumber\":9,\"spentOn\":\"2026-10-09\",\"hours\":1}").andExpect(status().isForbidden());
        assertThat(body(send(viewer, get("/api/v1/time-entries").param("workPackage", "9"), "")).size()).isZero();
        assertThat(body(send(viewer, get("/api/v1/time-entries/report").param("project", "web-platform").param("from", "2026-10-01").param("to", "2026-10-31"), ""))
                .get("total").decimalValue()).isEqualByComparingTo("0");

        // the module switched off: no logging there
        var modules = db.select(PROJECTS.ENABLED_MODULES).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq("web-platform")).fetchOne(PROJECTS.ENABLED_MODULES);
        try {
            send(admin, patch("/api/v1/projects/web-platform"), "{\"enabledModules\":[\"work_packages\",\"gantt\"]}").andExpect(status().isOk());
            send(member, post("/api/v1/time-entries"), "{\"workPackageNumber\":9,\"spentOn\":\"2026-10-09\",\"hours\":1}").andExpect(status().isForbidden());
            var week = body(send(member, get("/api/v1/time-entries/week").param("start", "2026-10-05"), ""));
            for (var row : week.get("rows")) assertThat(row.get("canLog").asBoolean()).isFalse();
        } finally {
            db.update(PROJECTS).set(PROJECTS.ENABLED_MODULES, modules).where(PROJECTS.IDENTIFIER.eq("web-platform")).execute();
        }
        send(member, delete("/api/v1/time-entries/" + mine.get("id").asString()), "").andExpect(status().isNoContent());
    }

    @Test
    void theReportSumsWhatICanSeeAndTheExportListsIt() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var range = "&from=2026-10-05&to=2026-10-11";
        var byUser = body(send(admin, get("/api/v1/time-entries/report?project=web-platform&groupBy=user" + range), "").andExpect(status().isOk()));
        assertThat(byUser.get("total").decimalValue()).isEqualByComparingTo("46.5");
        assertThat(byUser.get("rows").get(0).get("label").asString()).isEqualTo("김서연"); // 16.5h, the most
        assertThat(byUser.get("rows").get(0).get("hours").decimalValue()).isEqualByComparingTo("16.5");
        var byActivity = body(send(admin, get("/api/v1/time-entries/report?project=web-platform&groupBy=activity" + range), ""));
        assertThat(byActivity.get("rows").get(0).get("label").asString()).isEqualTo("개발");
        var byDay = body(send(admin, get("/api/v1/time-entries/report?groupBy=day" + range), ""));
        assertThat(byDay.get("rows").get(0).get("key").asString()).isEqualTo("2026-10-05");
        send(admin, get("/api/v1/time-entries/report?groupBy=nope" + range), "").andExpect(status().isUnprocessableContent());
        send(admin, get("/api/v1/time-entries/report?from=2026-01-01&to=2027-06-01"), "").andExpect(status().isUnprocessableContent());

        var file = send(admin, get("/api/v1/time-entries/export?project=web-platform" + range), "").andExpect(status().isOk()).andReturn().getResponse();
        assertThat(file.getContentType()).contains("spreadsheetml");
        assertThat(new String(file.getContentAsByteArray(), 0, 2)).isEqualTo("PK");
    }

    @Test
    void entriesFollowAMovedWorkPackageAndActivitiesAreTheAdministratorS() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var type = body(send(admin, get("/api/v1/work-packages/11"), "")).get("type").get("id").asString();
        long n = body(send(admin, post("/api/v1/projects/web-platform/work-packages"), "{\"subject\":\"옮길 작업\",\"typeId\":\"" + type + "\"}")
                .andExpect(status().isCreated())).get("number").asLong();
        try {
            send(admin, post("/api/v1/time-entries"), "{\"workPackageNumber\":" + n + ",\"spentOn\":\"2026-10-09\",\"hours\":1}").andExpect(status().isCreated());
            send(admin, post("/api/v1/work-packages/" + n + "/move"), "{\"targetProject\":\"mobile-app\"}").andExpect(status().isOk());
            var moved = body(send(admin, get("/api/v1/time-entries").param("workPackage", String.valueOf(n)), ""));
            assertThat(moved.get(0).get("project").get("identifier").asString()).isEqualTo("mobile-app");
        } finally {
            send(admin, delete("/api/v1/work-packages/" + n), "").andExpect(status().is2xxSuccessful());
        }

        var member = login("haneul.lee@openpms.local");
        send(member, post("/api/v1/admin/time-activities"), "{\"name\":\"고객 지원\"}").andExpect(status().isForbidden());
        var support = body(send(admin, post("/api/v1/admin/time-activities"), "{\"name\":\"고객 지원\",\"isDefault\":true}").andExpect(status().isCreated()));
        assertThat(support.get("isDefault").asBoolean()).isTrue();
        send(admin, post("/api/v1/admin/time-activities"), "{\"name\":\"고객 지원\"}").andExpect(status().isUnprocessableContent());
        send(admin, patch("/api/v1/admin/time-activities/" + support.get("id").asString()), "{\"active\":false}").andExpect(status().isUnprocessableContent());
        var dev = db.select(TIME_ACTIVITIES.ID).from(TIME_ACTIVITIES).where(TIME_ACTIVITIES.NAME.eq("개발")).fetchOne(TIME_ACTIVITIES.ID);
        send(admin, patch("/api/v1/admin/time-activities/" + dev), "{\"isDefault\":true}").andExpect(status().isOk());
        send(admin, patch("/api/v1/admin/time-activities/" + support.get("id").asString()), "{\"active\":false}").andExpect(status().isOk());
        var offered = body(send(member, get("/api/v1/time-activities"), ""));
        for (var a : offered) assertThat(a.get("name").asString()).isNotEqualTo("고객 지원");
        send(member, post("/api/v1/time-entries"), "{\"workPackageNumber\":9,\"spentOn\":\"2026-10-09\",\"hours\":1,\"activityId\":\"" + support.get("id").asString() + "\"}")
                .andExpect(status().isUnprocessableContent());
    }
}

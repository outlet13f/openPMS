package com.openpms.itsm;

import static com.openpms.db.Tables.CUSTOM_FIELDS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A catalog item requested from the portal: the item's type, priority, group and form, and its approval rows (ITSM 2단계). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CatalogFormIT {
    private static final String ALEX = "alex.kim@openpms.local";
    private static final String STEPS = "[{\"stepNo\":1,\"kind\":\"requester_manager\"},{\"stepNo\":2,\"kind\":\"group_lead\"}]";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    ItsmFixture f;
    MockHttpSession admin, agent, requester;
    String desk, group, field, option, fieldName;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        desk = f.newDesk(admin);
        group = f.body(f.send(admin, post("/api/v1/admin/groups"), "{\"name\":\"카탈로그 시험 " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"leadUserId\":\"" + ItsmFixture.JUNHO + "\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        fieldName = "장비 종류 " + UUID.randomUUID().toString().substring(0, 6);
        var cf = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + fieldName + "\",\"format\":\"list\",\"isForAll\":false,"
                + "\"options\":[{\"value\":\"노트북\"},{\"value\":\"데스크톱\"}],\"typeIds\":[\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\"]}")
                .andExpect(status().isCreated()).andReturn());
        field = cf.get("id").asString();
        option = cf.get("options").get(0).get("id").asString();
        f.send(admin, put("/api/v1/projects/" + desk + "/custom-fields"), "{\"customFieldIds\":[\"" + field + "\"]}").andExpect(status().isOk());
    }

    @AfterEach void tearDown() throws Exception {
        f.send(admin, delete("/api/v1/admin/custom-fields/" + field), "");
        f.send(admin, delete("/api/v1/admin/groups/" + group), "");
    }

    /** The item with both steps, routed to the test group, with the list field as a required form field. */
    String item(UUID groupId) throws Exception {
        var id = f.newItem(admin, desk, "노트북 신청 " + UUID.randomUUID().toString().substring(0, 6), STEPS, groupId);
        f.send(admin, patch("/api/v1/projects/" + desk + "/service-desk/catalog/items/" + id),
                "{\"defaultPriorityId\":\"" + ItsmFixture.URGENT + "\",\"fields\":[{\"customFieldId\":\"" + field + "\",\"isRequired\":true}]}")
                .andExpect(status().isOk());
        return id;
    }

    String request(String itemId, String extra) {
        return "{\"catalogItemId\":\"" + itemId + "\",\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"subject\":\"노트북이 필요합니다\"" + extra + "}";
    }

    String withOption() {
        return ",\"customValues\":{\"" + field + "\":\"" + option + "\"}";
    }

    private UUID idOf(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
    }

    private UUID managerOf(UUID user) {
        return db.select(USERS.MANAGER_ID).from(USERS).where(USERS.ID.eq(user)).fetchOne(USERS.MANAGER_ID);
    }

    private UUID wpId(long number) {
        return db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(number)).fetchOne(WORK_PACKAGES.ID);
    }

    @Test void anItemRequestWaitsForApprovalWithTheClockPaused() throws Exception {
        var alex = idOf(ALEX);
        var before = managerOf(ItsmFixture.MINJI);
        try {
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + alex + "\"}").andExpect(status().isOk());
            var itemId = item(UUID.fromString(group));
            var r = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), request(itemId, withOption()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status.name").value("승인 대기"))
                    .andExpect(jsonPath("$.type.id").value(ItsmIds.TYPE_SERVICE_REQUEST.toString()))
                    .andExpect(jsonPath("$.item.id").value(itemId))
                    .andReturn());
            var n = r.get("number").asLong();
            var wp = wpId(n);
            var t = db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne();
            assertThat(t.getCatalogItemId()).isEqualTo(UUID.fromString(itemId));
            assertThat(t.getAssignmentGroupId()).isEqualTo(UUID.fromString(group));
            assertThat(t.getPausedSince()).as("승인 대기 pauses the SLA from the start").isNotNull();
            assertThat(db.select(WORK_PACKAGES.PRIORITY_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(wp)).fetchOne(WORK_PACKAGES.PRIORITY_ID))
                    .isEqualTo(ItsmFixture.URGENT);
            var rows = db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wp)).orderBy(TICKET_APPROVALS.STEP_NO).fetch();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getApproverId()).isEqualTo(alex);
            assertThat(rows.get(0).getDecision()).isEqualTo("pending");
            assertThat(rows.get(1).getApproverId()).isEqualTo(ItsmFixture.JUNHO);
            assertThat(rows.get(1).getDecision()).isEqualTo("waiting");
            var alexName = db.select(USERS.NAME).from(USERS).where(USERS.ID.eq(alex)).fetchOne(USERS.NAME);
            var junhoName = db.select(USERS.NAME).from(USERS).where(USERS.ID.eq(ItsmFixture.JUNHO)).fetchOne(USERS.NAME);
            f.send(requester, get("/api/v1/portal/tickets/" + n), "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvals[0].stepNo").value(1))
                    .andExpect(jsonPath("$.approvals[0].approverName").value(alexName))
                    .andExpect(jsonPath("$.approvals[0].decision").value("pending"))
                    .andExpect(jsonPath("$.approvals[1].approverName").value(junhoName))
                    .andExpect(jsonPath("$.approvals[1].decision").value("waiting"))
                    .andExpect(jsonPath("$.fields[?(@.name == '" + fieldName + "')].value").value(Matchers.contains("노트북")));
        } finally {
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), before == null ? "{\"managerId\":null}" : "{\"managerId\":\"" + before + "\"}");
        }
    }

    @Test void withoutApproversItGoesStraightToReceived() throws Exception {
        var before = managerOf(ItsmFixture.DOYOON);
        var other = f.login(ItsmFixture.OTHER_REQUESTER);
        try {
            if (before != null) f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.DOYOON), "{\"managerId\":null}").andExpect(status().isOk());
            var itemId = item(null);
            var n = f.body(f.send(other, post("/api/v1/portal/desks/" + desk + "/tickets"), request(itemId, withOption()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status.name").value("접수"))
                    .andExpect(jsonPath("$.approvals[*].decision").value(Matchers.everyItem(Matchers.is("skipped"))))
                    .andExpect(jsonPath("$.approvals[*].approverName").value(Matchers.everyItem(Matchers.nullValue())))
                    .andReturn()).get("number").asLong();
            var wp = wpId(n);
            assertThat(db.select(SERVICE_TICKETS.PAUSED_SINCE).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wp)).fetchOne(SERVICE_TICKETS.PAUSED_SINCE))
                    .isNull();
            var rows = db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wp)).fetch();
            assertThat(rows).hasSize(2).allMatch(x -> "skipped".equals(x.getDecision()) && "no_approver".equals(x.getSkipReason()));
            f.send(agent, get("/api/v1/work-packages/" + n + "/activities"), "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].details[*].to", Matchers.hasItems("1단계 관리자 건너뜀: 승인자 없음", "2단계 그룹 책임자 건너뜀: 승인자 없음")))
                    .andExpect(jsonPath("$[*].details[?(@.field =~ /approval:.*/)].label", Matchers.everyItem(Matchers.is("승인"))));
        } finally {
            if (before != null) f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.DOYOON), "{\"managerId\":\"" + before + "\"}");
        }
    }

    @Test void requiredItemFieldsAreChecked() throws Exception {
        var itemId = item(null);
        f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), request(itemId, ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("cf:" + field))
                .andExpect(jsonPath("$.errors[0].message").value(fieldName + ": 값을 입력하세요."));
    }

    @Test void inactiveItemsCannotBeRequested() throws Exception {
        var itemId = item(null);
        f.send(requester, get("/api/v1/portal/desks"), "")
                .andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].catalog[*].items[*].id", Matchers.hasItem(itemId)))
                // the item has no category: it sits under the made-up 기타 with no id
                .andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].catalog[*].name", Matchers.hasItem("기타")))
                .andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].catalog[*].items[*].fields[*].required", Matchers.hasItem(true)));
        f.send(admin, patch("/api/v1/projects/" + desk + "/service-desk/catalog/items/" + itemId), "{\"isActive\":false}").andExpect(status().isOk());
        f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), request(itemId, withOption()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("catalogItem"))
                .andExpect(jsonPath("$.errors[0].message").value("요청할 수 없는 항목입니다."));
        f.send(requester, get("/api/v1/portal/desks"), "")
                .andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].catalog[*].items[*].id", Matchers.not(Matchers.hasItem(itemId))));
        // an item of another desk is not requestable here either
        f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), request(UUID.randomUUID().toString(), withOption()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("catalogItem"));
    }

    @Test void fieldsOutsideTheItemAreDropped() throws Exception {
        var name = "포털 공개 " + UUID.randomUUID().toString().substring(0, 6);
        var outside = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + name + "\",\"format\":\"string\",\"isForAll\":false,"
                + "\"typeIds\":[\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\"]}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        try {
            db.update(CUSTOM_FIELDS).set(CUSTOM_FIELDS.PORTAL_VISIBLE, true).where(CUSTOM_FIELDS.ID.eq(UUID.fromString(outside))).execute();
            f.send(admin, put("/api/v1/projects/" + desk + "/custom-fields"), "{\"customFieldIds\":[\"" + field + "\",\"" + outside + "\"]}").andExpect(status().isOk());
            var itemId = item(null);
            var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"),
                    request(itemId, ",\"customValues\":{\"" + field + "\":\"" + option + "\",\"" + outside + "\":\"몰래 넣은 값\"}"))
                    .andExpect(status().isCreated()).andReturn()).get("number").asLong();
            var values = db.select(WORK_PACKAGES.CUSTOM_VALUES).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n)).fetchOne(WORK_PACKAGES.CUSTOM_VALUES).data();
            assertThat(values).contains(field).doesNotContain(outside).doesNotContain("몰래 넣은 값");
        } finally {
            f.send(admin, delete("/api/v1/admin/custom-fields/" + outside), "");
        }
    }

    @Test void aRequestWithoutAnItemStillWorks() throws Exception {
        var n = f.body(f.send(requester, post("/api/v1/portal/desks/" + desk + "/tickets"), "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"subject\":\"프린터 고장\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status.name").value("접수"))
                .andExpect(jsonPath("$.item").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.approvals").isEmpty())
                .andReturn()).get("number").asLong();
        assertThat(db.fetchCount(TICKET_APPROVALS, TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId(n)))).isZero();
    }
}

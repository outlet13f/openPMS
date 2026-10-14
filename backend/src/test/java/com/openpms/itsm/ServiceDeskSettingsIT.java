package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ServiceDeskSettingsIT {
    @Autowired WebApplicationContext context;
    @Autowired ServiceDeskConfig config;
    ItsmFixture f;

    @BeforeEach void setUp() {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
    }

    @Test void managerReadsAndReplacesPoliciesAndTheMatrix() throws Exception {
        var admin = f.login(ItsmFixture.ADMIN);
        var desk = f.newDesk(admin);
        f.send(admin, get("/api/v1/projects/" + desk + "/service-desk/settings"), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies.length()").value(2))
                .andExpect(jsonPath("$.matrix.length()").value(9));
        var p = f.body(f.send(admin, get("/api/v1/projects/" + desk), "").andReturn()).get("id").asString();
        assertThat(config.matrixPriority(java.util.UUID.fromString(p), 1, 1)).contains(ItsmFixture.URGENT);
        assertThat(config.policyFor(java.util.UUID.fromString(p), ItsmIds.TYPE_INCIDENT, ItsmFixture.URGENT))
                .hasValueSatisfying(x -> assertThat(x.calendar().allDay()).isTrue());
    }

    @Test void agentsReadButCannotChangeSettings() throws Exception {
        var admin = f.login(ItsmFixture.ADMIN);
        var desk = f.newDesk(admin);
        var agent = f.login(ItsmFixture.AGENT);
        f.send(agent, get("/api/v1/projects/" + desk + "/service-desk/settings"), "").andExpect(status().isOk());
        f.send(agent, put("/api/v1/projects/" + desk + "/service-desk/settings"), "{\"policies\":[],\"matrix\":[]}").andExpect(status().isForbidden());
    }

    @Test void nonMembersSeeNothing() throws Exception {
        var admin = f.login(ItsmFixture.ADMIN);
        var desk = f.newDesk(admin);
        var requester = f.login(ItsmFixture.REQUESTER);
        f.send(requester, get("/api/v1/projects/" + desk + "/service-desk/settings"), "").andExpect(status().isNotFound());
    }

    @Test void policiesMustBeValid() throws Exception {
        var admin = f.login(ItsmFixture.ADMIN);
        var desk = f.newDesk(admin);
        // resolve before first response
        f.send(admin, put("/api/v1/projects/" + desk + "/service-desk/settings"), "{\"policies\":[{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT
                + "\",\"priorityId\":\"" + ItsmFixture.NORMAL + "\",\"calendarId\":\"" + ItsmFixture.CAL_BUSINESS
                + "\",\"firstResponseMinutes\":60,\"resolveMinutes\":30}],\"matrix\":[]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("policies[0].resolveMinutes"));
        // the same type and priority twice
        var one = "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"priorityId\":\"" + ItsmFixture.NORMAL + "\",\"calendarId\":\"" + ItsmFixture.CAL_BUSINESS
                + "\",\"firstResponseMinutes\":60,\"resolveMinutes\":90}";
        f.send(admin, put("/api/v1/projects/" + desk + "/service-desk/settings"), "{\"policies\":[" + one + "," + one + "],\"matrix\":[]}")
                .andExpect(status().isUnprocessableContent());
        // a type that is not a ticket type
        f.send(admin, put("/api/v1/projects/" + desk + "/service-desk/settings"), "{\"policies\":[{\"typeId\":\"dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc\""
                + ",\"priorityId\":\"" + ItsmFixture.NORMAL + "\",\"calendarId\":\"" + ItsmFixture.CAL_BUSINESS
                + "\",\"firstResponseMinutes\":60,\"resolveMinutes\":90}],\"matrix\":[]}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test void onlyAdminsEditCalendarsAndAUsedCalendarStays() throws Exception {
        var admin = f.login(ItsmFixture.ADMIN);
        f.newDesk(admin);
        var agent = f.login(ItsmFixture.AGENT);
        var body = "{\"name\":\"야간 " + java.util.UUID.randomUUID().toString().substring(0, 6) + "\",\"mode\":\"business\",\"timeZone\":\"Asia/Seoul\","
                + "\"hours\":[{\"day\":1,\"start\":\"18:00\",\"end\":\"23:00\"}]}";
        f.send(agent, post("/api/v1/service-calendars"), body).andExpect(status().isForbidden());
        var made = f.body(f.send(admin, post("/api/v1/service-calendars"), body).andExpect(status().isCreated()).andReturn());
        f.send(admin, patch("/api/v1/service-calendars/" + made.get("id").asString()),
                "{\"hours\":[{\"day\":1,\"start\":\"23:00\",\"end\":\"18:00\"}]}").andExpect(status().isUnprocessableContent());
        f.send(admin, patch("/api/v1/service-calendars/" + made.get("id").asString()),
                "{\"hours\":[{\"day\":1,\"start\":\"09:00\",\"end\":\"12:00\"},{\"day\":1,\"start\":\"11:00\",\"end\":\"14:00\"}]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("hours"));
        f.send(admin, delete("/api/v1/service-calendars/" + made.get("id").asString()), "").andExpect(status().isNoContent());
        f.send(admin, delete("/api/v1/service-calendars/" + ItsmFixture.CAL_BUSINESS), "").andExpect(status().isConflict());
    }

    @Test void aServiceDeskProjectCannotBePublic() throws Exception {
        var admin = f.login(ItsmFixture.ADMIN);
        var desk = f.newDesk(admin);
        f.send(admin, patch("/api/v1/projects/" + desk), "{\"isPublic\":true}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("isPublic"));
        var identifier = "pub-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        f.send(admin, post("/api/v1/projects"), "{\"name\":\"공개 " + identifier + "\",\"identifier\":\"" + identifier + "\",\"isPublic\":true}")
                .andExpect(status().isCreated());
        f.send(admin, patch("/api/v1/projects/" + identifier), "{\"enabledModules\":[\"work_packages\",\"service_desk\"]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("enabledModules"));
    }
}

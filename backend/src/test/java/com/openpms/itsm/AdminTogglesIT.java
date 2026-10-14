package com.openpms.itsm;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The two service desk flags operators set on existing admin screens: 포털에 공개 (custom fields) and SLA 멈춤 (statuses). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class AdminTogglesIT {
    @Autowired WebApplicationContext context;
    ItsmFixture f;
    MockHttpSession admin, requester;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        requester = f.login(ItsmFixture.REQUESTER);
    }

    @Test void slaPauseIsSetOnAStatusAndReadBack() throws Exception {
        var name = "외부 대기 " + java.util.UUID.randomUUID().toString().substring(0, 6);
        var id = f.body(f.send(admin, post("/api/v1/admin/statuses"), "{\"name\":\"" + name + "\",\"color\":\"#5f6873\",\"slaPause\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slaPause").value(true))
                .andReturn()).get("id").asString();
        try {
            f.send(admin, get("/api/v1/admin/statuses"), "").andExpect(jsonPath("$[?(@.id == '" + id + "')].slaPause").value(Matchers.contains(true)));
            f.send(admin, patch("/api/v1/admin/statuses/" + id), "{\"slaPause\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.slaPause").value(false));
            f.send(admin, get("/api/v1/admin/statuses"), "").andExpect(jsonPath("$[?(@.id == '" + id + "')].slaPause").value(Matchers.contains(false)));
        } finally {
            f.send(admin, delete("/api/v1/admin/statuses/" + id), "");
        }
        // 답변 대기 of the dev data pauses, 처리 중 does not
        f.send(admin, get("/api/v1/admin/statuses"), "")
                .andExpect(jsonPath("$[?(@.id == '" + ItsmIds.STATUS_WAITING_REQUESTER + "')].slaPause").value(Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.id == '" + ItsmIds.STATUS_HANDLING + "')].slaPause").value(Matchers.contains(false)));
    }

    @Test void aFieldMadePortalVisibleThroughTheAdminApiShowsOnTheDesk() throws Exception {
        var desk = f.newDesk(admin);
        var name = "사용 위치 " + java.util.UUID.randomUUID().toString().substring(0, 6);
        var field = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + name + "\",\"format\":\"string\",\"isForAll\":false,"
                + "\"typeIds\":[\"" + ItsmIds.TYPE_INCIDENT + "\"]}").andExpect(status().isCreated()).andExpect(jsonPath("$.portalVisible").value(false))
                .andReturn()).get("id").asString();
        try {
            f.send(admin, put("/api/v1/projects/" + desk + "/custom-fields"), "{\"customFieldIds\":[\"" + field + "\"]}").andExpect(status().isOk());
            f.send(requester, get("/api/v1/portal/desks"), "")
                    .andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].fields[*].name", Matchers.not(Matchers.hasItem(name))));
            f.send(admin, patch("/api/v1/admin/custom-fields/" + field), "{\"portalVisible\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.portalVisible").value(true));
            f.send(requester, get("/api/v1/portal/desks"), "")
                    .andExpect(jsonPath("$[?(@.identifier == '" + desk + "')].fields[*].name", Matchers.hasItem(name)));
        } finally {
            f.send(admin, delete("/api/v1/admin/custom-fields/" + field), "");
        }
    }

    @Test void theRequesterWorkflowIsOnTheWorkflowScreen() throws Exception {
        f.send(admin, get("/api/v1/admin/roles"), "").andExpect(jsonPath("$[?(@.id == '" + ItsmIds.ROLE_REQUESTER + "')].builtin").value(Matchers.contains("requester")));
        f.send(admin, get("/api/v1/admin/workflows?typeId=" + ItsmIds.TYPE_INCIDENT + "&roleId=" + ItsmIds.ROLE_REQUESTER), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions.length()").value(6))
                .andExpect(jsonPath("$.transitions", Matchers.hasItem(java.util.List.of(ItsmIds.STATUS_WAITING_REQUESTER.toString(), ItsmIds.STATUS_HANDLING.toString()))))
                .andExpect(jsonPath("$.transitions", Matchers.hasItem(java.util.List.of(ItsmIds.STATUS_RESOLVED.toString(), ItsmIds.STATUS_CLOSED.toString()))));
    }
}

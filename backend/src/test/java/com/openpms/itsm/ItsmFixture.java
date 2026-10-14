package com.openpms.itsm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup for the ITSM ITs. The test database is shared by every class, so each test makes its own service desk
 * project (sd-xxxxxxxx) with the dev accounts: 김서연 (admin, manager of the new project), 박준호 (agent, 멤버 role),
 * 최민지 and 한도윤 (requesters, not members).
 */
final class ItsmFixture {
    static final String ADMIN = "seoyeon.kim@openpms.local";
    static final String AGENT = "junho.park@openpms.local";
    static final String REQUESTER = "minji.choi@openpms.local";
    static final String OTHER_REQUESTER = "doyoon.han@openpms.local";
    static final String DEV = "openpms!";
    static final UUID JUNHO = UUID.fromString("c48ce4ad-3a64-55ab-a95c-2902395dbb46");
    static final UUID MINJI = UUID.fromString("1fe0bccd-f730-556b-bc23-09b76271d5ab");
    static final UUID DOYOON = UUID.fromString("50aa22c1-a4af-5b91-905b-7c48aa4619c2");
    static final UUID ROLE_MEMBER = UUID.fromString("6d4333fb-e975-5213-8ebc-552cca26e1d4");
    static final UUID NORMAL = UUID.fromString("e2513703-c41d-5575-aca0-fd084ec26e06");
    static final UUID URGENT = UUID.fromString("ad169c7c-6852-59e6-b997-4cb8575645bb");
    static final UUID CAL_BUSINESS = UUID.fromString("27ee2635-3f4b-5407-8498-78ce0dbf371c");
    static final UUID CAL_24X7 = UUID.fromString("867264b7-e4ac-5081-b799-9329cc748825");

    final MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    ItsmFixture(MockMvc mvc) {
        this.mvc = mvc;
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + DEV + "\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /**
     * A private project with the service desk on, both ticket types, 박준호 as agent and SLA policies: incidents at
     * 보통 use business hours (respond 60 min, resolve 240 min), at 긴급 24x7 (15 / 120). Every matrix cell is 보통
     * except impact 1 × urgency 1 = 긴급. Returns the identifier.
     */
    String newDesk(MockHttpSession admin) throws Exception {
        var identifier = "sd-" + UUID.randomUUID().toString().substring(0, 8);
        var created = body(send(admin, post("/api/v1/projects"), "{\"name\":\"서비스 데스크 " + identifier + "\",\"identifier\":\"" + identifier + "\"}")
                .andExpect(status().isCreated()).andReturn());
        var id = created.get("id").asString();
        send(admin, patch("/api/v1/projects/" + id), "{\"enabledModules\":[\"work_packages\",\"service_desk\"]}").andExpect(status().isOk());
        send(admin, put("/api/v1/projects/" + id + "/types"),
                "{\"typeIds\":[\"" + ItsmIds.TYPE_INCIDENT + "\",\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\"]}").andExpect(status().isOk());
        send(admin, post("/api/v1/projects/" + id + "/members"),
                "{\"principalIds\":[\"" + JUNHO + "\"],\"roleIds\":[\"" + ROLE_MEMBER + "\"]}").andExpect(status().is2xxSuccessful());
        var matrix = new StringBuilder();
        for (int i = 1; i <= 3; i++)
            for (int u = 1; u <= 3; u++) {
                if (matrix.length() > 0) matrix.append(',');
                matrix.append("{\"impact\":").append(i).append(",\"urgency\":").append(u).append(",\"priorityId\":\"")
                        .append(i == 1 && u == 1 ? URGENT : NORMAL).append("\"}");
            }
        send(admin, put("/api/v1/projects/" + identifier + "/service-desk/settings"), "{\"policies\":["
                + "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"priorityId\":\"" + NORMAL + "\",\"calendarId\":\"" + CAL_BUSINESS + "\",\"firstResponseMinutes\":60,\"resolveMinutes\":240},"
                + "{\"typeId\":\"" + ItsmIds.TYPE_INCIDENT + "\",\"priorityId\":\"" + URGENT + "\",\"calendarId\":\"" + CAL_24X7 + "\",\"firstResponseMinutes\":15,\"resolveMinutes\":120}"
                + "],\"matrix\":[" + matrix + "]}").andExpect(status().isOk());
        return identifier;
    }
}

package com.openpms.cicd;

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
 * Shared setup for the CI/CD ITs. The test database is shared by every class, so each test makes its own projects
 * (cd-xxxxxxxx) and uses its own connection names.
 */
public final class CicdFixture {
    public static final String ADMIN = "seoyeon.kim@openpms.local";
    public static final String JUNHO_EMAIL = "junho.park@openpms.local";
    public static final String MINJI_EMAIL = "minji.choi@openpms.local";
    public static final String DEV = "openpms!";
    public static final UUID JUNHO = UUID.fromString("c48ce4ad-3a64-55ab-a95c-2902395dbb46");
    public static final UUID MINJI = UUID.fromString("1fe0bccd-f730-556b-bc23-09b76271d5ab");
    public static final UUID DOYOON = UUID.fromString("50aa22c1-a4af-5b91-905b-7c48aa4619c2");
    public static final UUID ROLE_MEMBER = UUID.fromString("6d4333fb-e975-5213-8ebc-552cca26e1d4");
    public static final UUID ROLE_OBSERVER = UUID.fromString("f957f36a-72ca-5379-b8b9-c0e7dabe2d1e");
    public static final UUID ROLE_DEV_LEAD = UUID.fromString("8e9c194d-fcdf-5c47-b081-ab2b2b61c89a");
    public static final UUID ROLE_CICD_ADMIN = UUID.fromString("bae8d8f2-850d-5250-9c69-25b1631be394");

    final MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    public CicdFixture(MockMvc mvc) {
        this.mvc = mvc;
    }

    public MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + DEV + "\"}")).andExpect(status().isOk());
        return s;
    }

    public ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** A private project with the given modules, 박준호 as 멤버 and 최민지 as 참관자. Returns the identifier. */
    public String newProject(MockHttpSession admin, String... modules) throws Exception {
        var identifier = "cd-" + UUID.randomUUID().toString().substring(0, 8);
        var id = body(send(admin, post("/api/v1/projects"), "{\"name\":\"개발 연동 " + identifier + "\",\"identifier\":\"" + identifier + "\"}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        var list = new StringBuilder();
        for (var m : modules) list.append(list.length() == 0 ? "" : ",").append('"').append(m).append('"');
        send(admin, patch("/api/v1/projects/" + id), "{\"enabledModules\":[" + list + "]}").andExpect(status().isOk());
        addMember(admin, id, JUNHO, ROLE_MEMBER);
        addMember(admin, id, MINJI, ROLE_OBSERVER);
        return identifier;
    }

    /** projectId: the id or the identifier. */
    public void addMember(MockHttpSession admin, String projectId, UUID userId, UUID roleId) throws Exception {
        send(admin, post("/api/v1/projects/" + projectId + "/members"),
                "{\"principalIds\":[\"" + userId + "\"],\"roleIds\":[\"" + roleId + "\"]}").andExpect(status().is2xxSuccessful());
    }

    /** Creates a connection as the given session. Returns [id, webhookSecret, hookKey]. token may be null. */
    public String[] newConnection(MockHttpSession session, String kind, String baseUrl, String token) throws Exception {
        var name = "연동 " + UUID.randomUUID();
        var in = "{\"kind\":\"" + kind + "\",\"name\":\"" + name + "\",\"baseUrl\":\"" + baseUrl + "\""
                + (token == null ? "" : ",\"apiToken\":\"" + token + "\"") + "}";
        var created = body(send(session, post("/api/v1/admin/cicd/connections"), in).andExpect(status().isCreated()).andReturn());
        var webhookUrl = created.at("/connection/webhookUrl").asString();
        return new String[] {created.at("/connection/id").asString(), created.get("webhookSecret").asString(),
                webhookUrl.substring(webhookUrl.lastIndexOf('/') + 1)};
    }

    /** Replaces the user's global roles with this one (the admin API sets them as a whole). */
    public void grantGlobal(MockHttpSession admin, UUID userId, UUID roleId) throws Exception {
        send(admin, put("/api/v1/admin/users/" + userId + "/global-roles"), "{\"roleIds\":[\"" + roleId + "\"]}").andExpect(status().isOk());
    }
}

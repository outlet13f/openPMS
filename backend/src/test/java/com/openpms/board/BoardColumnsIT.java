package com.openpms.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S15 board management on a board made for the test: add, reorder, rename rules, remove. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class BoardColumnsIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MockHttpSession login(String email) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return session;
    }

    ResultActions send(MockHttpSession s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static java.util.List<String> titles(JsonNode board) {
        var out = new ArrayList<String>();
        board.get("columns").forEach(c -> out.add(c.get("title").asString()));
        return out;
    }

    @Test
    void columnsAreAddedOnceMovedAndRemoved() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"열 시험\",\"kind\":\"status\"}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        try {
            var first = board.get("columns").get(0);
            send(admin, post("/api/v1/boards/" + id + "/columns"), "{\"valueId\":\"" + first.get("valueId").asString() + "\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].message").value("이미 있는 열입니다."));
            send(admin, post("/api/v1/boards/" + id + "/columns"), "{\"valueId\":\"00000000-0000-0000-0000-000000000000\"}")
                    .andExpect(status().isUnprocessableContent());
            send(admin, patch("/api/v1/board-columns/" + first.get("id").asString()), "{\"name\":\"x\"}").andExpect(status().isUnprocessableContent());

            var before = titles(board);
            var moved = body(send(admin, patch("/api/v1/board-columns/" + first.get("id").asString()), "{\"position\":99}").andExpect(status().isOk()));
            var after = titles(moved);
            assertThat(after.get(after.size() - 1)).isEqualTo(before.get(0));
            assertThat(after).hasSameSizeAs(before);

            var removed = body(send(admin, delete("/api/v1/board-columns/" + first.get("id").asString()), "").andExpect(status().isOk()));
            assertThat(titles(removed)).hasSize(before.size() - 1).doesNotContain(before.get(0));

            var renamed = body(send(admin, patch("/api/v1/boards/" + id), "{\"name\":\"  새 이름 \"}").andExpect(status().isOk()));
            assertThat(renamed.get("name").asString()).isEqualTo("새 이름");
            send(login("doyoon.han@openpms.local"), patch("/api/v1/boards/" + id), "{\"name\":\"x\"}").andExpect(status().isNotFound());
        } finally {
            send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
        }
    }
}

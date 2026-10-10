package com.openpms.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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

/** S15·S16 보드: basic boards filled by hand, saved and temporary filters, cards made from a column get its value. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class BoardsIT {
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

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static List<Long> numbers(JsonNode column) {
        var out = new ArrayList<Long>();
        column.get("cards").forEach(c -> out.add(c.get("number").asLong()));
        return out;
    }

    static JsonNode column(JsonNode board, String title) {
        for (var c : board.get("columns")) if (c.get("title").asString().equals(title)) return c;
        throw new AssertionError("no column " + title);
    }

    @Test
    void aBasicBoardHoldsWhatTheTeamPutsThereAndChangesNoWorkPackage() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"이번 주 할 일\",\"kind\":\"basic\"}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        try {
            assertThat(board.get("kind").asString()).isEqualTo("basic");
            var todo = column(board, "할 일");
            assertThat(numbers(todo)).isEmpty();
            board = body(send(admin, post("/api/v1/boards/" + id + "/columns"), "{\"name\":\"진행\"}").andExpect(status().isOk()));
            var doing = column(board, "진행");
            send(admin, post("/api/v1/boards/" + id + "/columns"), "{\"name\":\"\"}").andExpect(status().isUnprocessableContent());

            var before = body(send(admin, get("/api/v1/work-packages/3"), "").andExpect(status().isOk()));
            send(admin, post("/api/v1/boards/" + id + "/cards"), "{\"columnId\":\"" + todo.get("id").asString() + "\",\"number\":3}").andExpect(status().isOk());
            board = body(send(admin, post("/api/v1/boards/" + id + "/cards"), "{\"columnId\":\"" + todo.get("id").asString() + "\",\"number\":5}").andExpect(status().isOk()));
            assertThat(numbers(column(board, "할 일"))).containsExactly(3L, 5L);
            // reorder inside the list, then move to the other list
            board = body(send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":5,\"toColumnId\":\"" + todo.get("id").asString() + "\",\"beforeNumber\":3}")
                    .andExpect(status().isOk()));
            assertThat(numbers(column(board, "할 일"))).containsExactly(5L, 3L);
            board = body(send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":3,\"toColumnId\":\"" + doing.get("id").asString() + "\",\"beforeNumber\":null}")
                    .andExpect(status().isOk()));
            assertThat(numbers(column(board, "할 일"))).containsExactly(5L);
            assertThat(numbers(column(board, "진행"))).containsExactly(3L);
            // off the board: the work package itself never changed
            board = body(send(admin, delete("/api/v1/boards/" + id + "/cards/3"), "").andExpect(status().isOk()));
            assertThat(numbers(column(board, "진행"))).isEmpty();
            var after = body(send(admin, get("/api/v1/work-packages/3"), "").andExpect(status().isOk()));
            assertThat(after.get("lockVersion").asInt()).isEqualTo(before.get("lockVersion").asInt());
            assertThat(after.get("status").get("id")).isEqualTo(before.get("status").get("id"));
            // a card that is not on the board cannot be moved; a work package of another project cannot be added
            send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":3,\"toColumnId\":\"" + doing.get("id").asString() + "\"}").andExpect(status().isNotFound());
        } finally {
            send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
        }
    }

    @Test
    void savedFiltersNarrowEveryColumnAndALookDoesNotSave() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"필터 시험\",\"kind\":\"status\"}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        try {
            int all = 0;
            for (var c : board.get("columns")) all += c.get("cards").size();
            var me = body(send(admin, get("/api/v1/auth/me"), "")).get("id").asString();
            var filters = "[{\"field\":\"assignee\",\"op\":\"eq\",\"values\":[\"" + me + "\"]}]";
            board = body(send(admin, patch("/api/v1/boards/" + id), "{\"filters\":" + filters + "}").andExpect(status().isOk()));
            assertThat(board.get("filters").get(0).get("field").asString()).isEqualTo("assignee");
            int mine = 0;
            for (var c : board.get("columns")) for (var card : c.get("cards")) {
                assertThat(card.get("assignee").get("id").asString()).isEqualTo(me);
                mine++;
            }
            assertThat(mine).isLessThan(all);
            // a look through other filters, without saving
            var look = body(send(admin, get("/api/v1/boards/" + id).param("filters", "[]"), "").andExpect(status().isOk()));
            int looked = 0;
            for (var c : look.get("columns")) looked += c.get("cards").size();
            assertThat(looked).isEqualTo(all);
            assertThat(body(send(admin, get("/api/v1/boards/" + id), "")).get("filters").size()).isEqualTo(1);
            send(admin, patch("/api/v1/boards/" + id), "{\"filters\":[{\"field\":\"nope\",\"op\":\"eq\",\"values\":[]}]}").andExpect(status().isUnprocessableContent());
            send(admin, get("/api/v1/boards/" + id).param("filters", "not json"), "").andExpect(status().isUnprocessableContent());
        } finally {
            send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
        }
    }

    @Test
    void aCardMadeFromAColumnGetsTheColumnsValue() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"만들기 시험\",\"kind\":\"status\"}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        Long number = null;
        try {
            var target = board.get("columns").get(1);
            var made = body(send(admin, post("/api/v1/boards/" + id + "/cards/new"), "{\"columnId\":\"" + target.get("id").asString() + "\",\"subject\":\"열에서 만든 작업\"}")
                    .andExpect(status().isCreated()));
            number = made.get("workPackage").get("number").asLong();
            assertThat(made.get("workPackage").get("status").get("id").asString()).isEqualTo(target.get("valueId").asString());
            assertThat(numbers(column(made.get("board"), target.get("title").asString()))).endsWith(number);
            send(admin, post("/api/v1/boards/" + id + "/cards/new"), "{\"columnId\":\"" + target.get("id").asString() + "\",\"subject\":\" \"}")
                    .andExpect(status().isUnprocessableContent());
            // not a basic board: cards come from the attribute, they are not put there by hand
            send(admin, post("/api/v1/boards/" + id + "/cards"), "{\"columnId\":\"" + target.get("id").asString() + "\",\"number\":3}").andExpect(status().isUnprocessableContent());
        } finally {
            if (number != null) send(admin, delete("/api/v1/work-packages/" + number), "").andExpect(status().is2xxSuccessful());
            send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
        }
    }
}

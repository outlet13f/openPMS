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

/** S15·S16 보드: basic boards filled by hand, subproject and parent-child boards, saved and temporary filters, cards made from a column get its value. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class BoardsIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonMapper json;
    @Autowired
    org.jooq.DSLContext db;

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
                // 공동 담당자: the filter matches anyone who has the card, not only its main assignee
                var ids = new java.util.ArrayList<String>();
                card.get("assignees").forEach(a -> ids.add(a.get("user").get("id").asString()));
                assertThat(ids).contains(me);
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
    void aSubprojectBoardShowsTheSubprojectsWorkAndMovingACardMovesTheWork() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var infra = body(send(admin, get("/api/v1/projects/infra"), "")).get("id").asString();
        send(admin, post("/api/v1/projects"), "{\"name\":\"임시 하위\",\"identifier\":\"infra-temp\",\"parentId\":\"" + infra + "\"}").andExpect(status().isCreated());
        String id = null;
        Long number = null;
        // the seed has boards switched off in 사내 인프라
        send(admin, patch("/api/v1/projects/infra"), "{\"enabledModules\":[\"work_packages\",\"gantt\",\"boards\"]}").andExpect(status().isOk());
        try {
            var board = body(send(admin, post("/api/v1/projects/infra/boards"), "{\"name\":\"하위 프로젝트\",\"kind\":\"subproject\"}").andExpect(status().isCreated()));
            id = board.get("id").asString();
            var backup = column(board, "백업 체계 개선");
            var temp = column(board, "임시 하위");
            assertThat(numbers(backup)).contains(21L); // seed: #21 is in 백업 체계 개선
            var made = body(send(admin, post("/api/v1/boards/" + id + "/cards/new"), "{\"columnId\":\"" + backup.get("id").asString() + "\",\"subject\":\"하위 프로젝트에서 만든 작업\"}")
                    .andExpect(status().isCreated()));
            number = made.get("workPackage").get("number").asLong();
            assertThat(made.get("workPackage").get("project").get("identifier").asString()).isEqualTo("infra-backup");
            var wp = body(send(admin, get("/api/v1/work-packages/" + number), ""));
            board = body(send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":" + number + ",\"toColumnId\":\"" + temp.get("id").asString()
                    + "\",\"beforeNumber\":null,\"lockVersion\":" + wp.get("lockVersion").asInt() + "}").andExpect(status().isOk()));
            assertThat(numbers(column(board, "임시 하위"))).containsExactly(number);
            assertThat(body(send(admin, get("/api/v1/work-packages/" + number), "")).get("project").get("identifier").asString()).isEqualTo("infra-temp");
            // only direct subprojects can be columns
            send(admin, post("/api/v1/boards/" + id + "/columns"), "{\"valueId\":\"" + infra + "\"}").andExpect(status().isUnprocessableContent());
            // a subproject moved elsewhere drops out of the board
            send(admin, patch("/api/v1/projects/infra-temp"), "{\"parentId\":null}").andExpect(status().isOk());
            board = body(send(admin, get("/api/v1/boards/" + id), "").andExpect(status().isOk()));
            for (var c : board.get("columns")) assertThat(c.get("title").asString()).isNotEqualTo("임시 하위");
        } finally {
            if (number != null) send(admin, delete("/api/v1/work-packages/" + number), "").andExpect(status().is2xxSuccessful());
            if (id != null) send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
            send(admin, delete("/api/v1/projects/infra-temp"), "{\"confirmIdentifier\":\"infra-temp\"}").andExpect(status().isNoContent());
            send(admin, patch("/api/v1/projects/infra"), "{\"enabledModules\":[\"work_packages\",\"gantt\"]}").andExpect(status().isOk());
        }
    }

    @Test
    void aParentChildBoardShowsChildrenUnderTheirParentAndMovingACardChangesTheParent() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"상위-하위\",\"kind\":\"parent_child\"}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        Long number = null;
        try {
            // seed: #1 and #7 have children; a column per parent, titled with its number and subject
            JsonNode one = null, seven = null;
            for (var c : board.get("columns")) {
                if (c.get("title").asString().startsWith("#1 ")) one = c;
                if (c.get("title").asString().startsWith("#7 ")) seven = c;
            }
            assertThat(one).isNotNull();
            assertThat(seven).isNotNull();
            for (var card : one.get("cards")) assertThat(body(send(admin, get("/api/v1/work-packages/" + card.get("number").asLong()), "")).get("parent").get("number").asLong()).isEqualTo(1L);
            var made = body(send(admin, post("/api/v1/boards/" + id + "/cards/new"), "{\"columnId\":\"" + one.get("id").asString() + "\",\"subject\":\"새 하위 작업\"}")
                    .andExpect(status().isCreated()));
            number = made.get("workPackage").get("number").asLong();
            assertThat(made.get("workPackage").get("parent").get("number").asLong()).isEqualTo(1L);
            var wp = body(send(admin, get("/api/v1/work-packages/" + number), ""));
            board = body(send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":" + number + ",\"toColumnId\":\"" + seven.get("id").asString()
                    + "\",\"beforeNumber\":null,\"lockVersion\":" + wp.get("lockVersion").asInt() + "}").andExpect(status().isOk()));
            assertThat(body(send(admin, get("/api/v1/work-packages/" + number), "")).get("parent").get("number").asLong()).isEqualTo(7L);
            // a work package of another project cannot be a column
            var other = db.select(com.openpms.db.Tables.WORK_PACKAGES.ID).from(com.openpms.db.Tables.WORK_PACKAGES).where(com.openpms.db.Tables.WORK_PACKAGES.NUMBER.eq(16L))
                    .fetchOne(com.openpms.db.Tables.WORK_PACKAGES.ID);
            send(admin, post("/api/v1/boards/" + id + "/columns"), "{\"valueId\":\"" + other + "\"}").andExpect(status().isUnprocessableContent());
        } finally {
            if (number != null) send(admin, delete("/api/v1/work-packages/" + number), "").andExpect(status().is2xxSuccessful());
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
            send(admin, delete("/api/v1/boards/" + id + "/cards/3"), "").andExpect(status().isUnprocessableContent());
        } finally {
            if (number != null) send(admin, delete("/api/v1/work-packages/" + number), "").andExpect(status().is2xxSuccessful());
            send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
        }
    }

    @Test
    void withoutBoardManageABoardCanBeLookedAtButNotChanged() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        // 최민지 is 참관자 in 웹 플랫폼: board.view without board.manage
        var viewer = login("minji.choi@openpms.local");
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"함께 보는 보드\",\"kind\":\"basic\",\"isPublic\":true}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        try {
            var todo = column(board, "할 일").get("id").asString();
            send(admin, post("/api/v1/boards/" + id + "/cards"), "{\"columnId\":\"" + todo + "\",\"number\":3}").andExpect(status().isOk());
            assertThat(numbers(column(body(send(viewer, get("/api/v1/boards/" + id), "").andExpect(status().isOk())), "할 일"))).containsExactly(3L);
            // a look through filters, without saving: allowed
            send(viewer, get("/api/v1/boards/" + id).param("filters", "[{\"field\":\"status\",\"op\":\"open\",\"values\":[]}]"), "")
                    .andExpect(status().isOk());
            // every change: refused
            send(viewer, post("/api/v1/boards/" + id + "/columns"), "{\"name\":\"진행\"}").andExpect(status().isForbidden());
            send(viewer, post("/api/v1/boards/" + id + "/cards"), "{\"columnId\":\"" + todo + "\",\"number\":5}").andExpect(status().isForbidden());
            send(viewer, post("/api/v1/boards/" + id + "/moves"), "{\"number\":3,\"toColumnId\":\"" + todo + "\",\"beforeNumber\":null}").andExpect(status().isForbidden());
            send(viewer, delete("/api/v1/boards/" + id + "/cards/3"), "").andExpect(status().isForbidden());
            send(viewer, patch("/api/v1/boards/" + id), "{\"filters\":[{\"field\":\"status\",\"op\":\"open\",\"values\":[]}]}").andExpect(status().isForbidden());
            send(viewer, patch("/api/v1/board-columns/" + todo), "{\"name\":\"바꾼 이름\"}").andExpect(status().isForbidden());
            send(viewer, delete("/api/v1/board-columns/" + todo), "").andExpect(status().isForbidden());
            send(viewer, delete("/api/v1/boards/" + id), "").andExpect(status().isForbidden());
            // nothing moved
            var now = body(send(admin, get("/api/v1/boards/" + id), "").andExpect(status().isOk()));
            assertThat(numbers(column(now, "할 일"))).containsExactly(3L);
            assertThat(now.get("filters").size()).isZero();
        } finally {
            send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
        }
    }

    @Test
    void aSubprojectCardTakesItsChildrenAlongAndNeedsTheRightsToMoveThere() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var infra = body(send(admin, get("/api/v1/projects/infra"), "")).get("id").asString();
        // 임시 하위: public, so 이하늘 (manager of 사내 인프라 and 백업 체계 개선, not a member here) sees it but may not put work in it
        send(admin, post("/api/v1/projects"), "{\"name\":\"임시 하위\",\"identifier\":\"infra-temp\",\"parentId\":\"" + infra + "\",\"isPublic\":true}").andExpect(status().isCreated());
        send(admin, patch("/api/v1/projects/infra"), "{\"enabledModules\":[\"work_packages\",\"gantt\",\"boards\"]}").andExpect(status().isOk());
        String id = null;
        Long parent = null;
        Long child = null;
        try {
            var board = body(send(admin, post("/api/v1/projects/infra/boards"), "{\"name\":\"하위 프로젝트\",\"kind\":\"subproject\",\"isPublic\":true}").andExpect(status().isCreated()));
            id = board.get("id").asString();
            var backup = column(board, "백업 체계 개선").get("id").asString();
            var temp = column(board, "임시 하위").get("id").asString();
            parent = body(send(admin, post("/api/v1/boards/" + id + "/cards/new"), "{\"columnId\":\"" + backup + "\",\"subject\":\"옮길 상위 작업\"}")
                    .andExpect(status().isCreated())).get("workPackage").get("number").asLong();
            var type = body(send(admin, get("/api/v1/work-packages/" + parent), "")).get("type").get("id").asString();
            child = body(send(admin, post("/api/v1/projects/infra-backup/work-packages"), "{\"subject\":\"따라갈 하위 작업\",\"typeId\":\"" + type + "\",\"parentNumber\":" + parent + "}")
                    .andExpect(status().isCreated())).get("number").asLong();

            // 이하늘 may see 임시 하위 but not move work into it: refused, nothing moved
            var haneul = login("haneul.lee@openpms.local");
            var lock = body(send(haneul, get("/api/v1/work-packages/" + parent), "")).get("lockVersion").asInt();
            var refused = send(haneul, post("/api/v1/boards/" + id + "/moves"), "{\"number\":" + parent + ",\"toColumnId\":\"" + temp + "\",\"beforeNumber\":null,\"lockVersion\":" + lock + "}")
                    .andReturn().getResponse().getStatus();
            assertThat(refused).isIn(403, 422);
            assertThat(body(send(admin, get("/api/v1/work-packages/" + parent), "")).get("project").get("identifier").asString()).isEqualTo("infra-backup");

            // the administrator moves it: the child goes along
            lock = body(send(admin, get("/api/v1/work-packages/" + parent), "")).get("lockVersion").asInt();
            send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":" + parent + ",\"toColumnId\":\"" + temp + "\",\"beforeNumber\":null,\"lockVersion\":" + lock + "}")
                    .andExpect(status().isOk());
            assertThat(body(send(admin, get("/api/v1/work-packages/" + parent), "")).get("project").get("identifier").asString()).isEqualTo("infra-temp");
            var moved = body(send(admin, get("/api/v1/work-packages/" + child), ""));
            assertThat(moved.get("project").get("identifier").asString()).isEqualTo("infra-temp");
            assertThat(moved.get("parent").get("number").asLong()).isEqualTo(parent);
        } finally {
            if (child != null) send(admin, delete("/api/v1/work-packages/" + child), "").andExpect(status().is2xxSuccessful());
            if (parent != null) send(admin, delete("/api/v1/work-packages/" + parent), "").andExpect(status().is2xxSuccessful());
            if (id != null) send(admin, delete("/api/v1/boards/" + id), "").andExpect(status().isNoContent());
            send(admin, delete("/api/v1/projects/infra-temp"), "{\"confirmIdentifier\":\"infra-temp\"}").andExpect(status().isNoContent());
            send(admin, patch("/api/v1/projects/infra"), "{\"enabledModules\":[\"work_packages\",\"gantt\"]}").andExpect(status().isOk());
        }
    }
}

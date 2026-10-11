package com.openpms.wp;

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

/** 공동 담당자 (fixes.md 6번): several people on one item, each with their hours; filters, notifications, boards and copies see them all. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CoAssigneesIT {
    static final String JUNHO = "c48ce4ad-3a64-55ab-a95c-2902395dbb46";
    static final String HANEUL = "f0712dee-a7e4-5b9a-a9c5-7d3682ce763a";
    static final String MINJI = "1fe0bccd-f730-556b-bc23-09b76271d5ab";
    static final String DOYOON = "50aa22c1-a4af-5b91-905b-7c48aa4619c2"; // not a member of web-platform
    static final String TASK = "dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc";

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

    JsonNode create(MockHttpSession s, String subject, String extra) throws Exception {
        return body(send(s, post("/api/v1/projects/web-platform/work-packages"), "{\"subject\":\"" + subject + "\",\"typeId\":\"" + TASK + "\"" + extra + "}")
                .andExpect(status().isCreated()));
    }

    static List<String> people(JsonNode wp) {
        var out = new ArrayList<String>();
        wp.get("assignees").forEach(a -> out.add(a.get("user").get("name").asString() + (a.get("workHours").isNull() ? "" : " " + a.get("workHours").asDouble())));
        return out;
    }

    List<Long> numbers(MockHttpSession s, String filter) throws Exception {
        var r = body(send(s, post("/api/v1/work-packages/query"), "{\"projectId\":\"web-platform\",\"pageSize\":200,\"spec\":{\"filters\":[" + filter
                + "],\"columns\":[\"subject\"],\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"hierarchy\":false}}").andExpect(status().isOk()));
        var out = new ArrayList<Long>();
        r.get("rows").forEach(x -> out.add(x.get("wp").get("number").asLong()));
        return out;
    }

    @Test
    void severalPeopleWithTheirOwnHoursAndTheOlderSingleAssigneeStillWorks() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var wp = create(admin, "공동 담당 확인", ",\"assignees\":[{\"userId\":\"" + JUNHO + "\",\"workHours\":8},{\"userId\":\"" + HANEUL + "\",\"workHours\":4.5}]");
        long n = wp.get("number").asLong();
        assertThat(people(wp)).containsExactly("박준호 8.0", "이하늘 4.5");
        assertThat(wp.get("assignee").get("name").asString()).as("the first is the main assignee").isEqualTo("박준호");

        // nobody twice, members only
        send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assignees\":[\"" + JUNHO + "\",\"" + JUNHO + "\"]}")
                .andExpect(status().isUnprocessableContent());
        send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assignees\":[\"" + DOYOON + "\"]}")
                .andExpect(status().isUnprocessableContent());

        // the whole list: a third person, hours changed; the activity says so
        wp = body(send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assignees\":[{\"userId\":\"" + JUNHO
                + "\",\"workHours\":6},{\"userId\":\"" + HANEUL + "\",\"workHours\":4.5},{\"userId\":\"" + MINJI + "\"}]}").andExpect(status().isOk()));
        assertThat(people(wp)).containsExactly("박준호 6.0", "이하늘 4.5", "최민지");
        var activity = body(send(admin, get("/api/v1/work-packages/" + n + "/activities"), ""));
        var last = activity.get(activity.size() - 1).get("details");
        assertThat(last.toString()).contains("담당자 목록").contains("박준호 6시간, 이하늘 4.5시간, 최민지");

        // the older way: someone already on it moves to the front with their hours; someone new takes the main place; nobody clears it
        wp = body(send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assigneeId\":\"" + HANEUL + "\"}"));
        assertThat(people(wp)).containsExactly("이하늘 4.5", "박준호 6.0", "최민지");
        wp = body(send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assigneeId\":\"" + JUNHO + "\"}"));
        assertThat(people(wp)).containsExactly("박준호 6.0", "이하늘 4.5", "최민지");
        wp = body(send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assigneeId\":null}"));
        assertThat(people(wp)).isEmpty();
        assertThat(wp.get("assignee").isNull()).isTrue();
    }

    @Test
    void filtersAndNotificationsSeeEveryAssignee() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        long n = create(admin, "함께 맡는 작업", ",\"assigneeId\":\"" + JUNHO + "\"").get("number").asLong();
        var wp = body(send(admin, get("/api/v1/work-packages/" + n), ""));
        send(admin, patch("/api/v1/work-packages/" + n), "{\"lockVersion\":" + wp.get("lockVersion").asInt() + ",\"assignees\":[\"" + JUNHO + "\",\"" + HANEUL + "\"]}")
                .andExpect(status().isOk());

        // 담당자 = 이하늘 finds it although 박준호 is the main assignee; 내게 할당됨 for 이하늘 too; 없음 does not
        assertThat(numbers(admin, "{\"field\":\"assignee\",\"op\":\"eq\",\"values\":[\"" + HANEUL + "\"]}")).contains(n);
        var haneul = login("haneul.lee@openpms.local");
        assertThat(numbers(haneul, "{\"field\":\"assignee\",\"op\":\"eq\",\"values\":[\"me\"]}")).contains(n);
        assertThat(numbers(admin, "{\"field\":\"assignee\",\"op\":\"neq\",\"values\":[\"" + HANEUL + "\"]}")).doesNotContain(n);
        assertThat(numbers(admin, "{\"field\":\"assignee\",\"op\":\"none\",\"values\":[]}")).doesNotContain(n);
        assertThat(numbers(admin, "{\"field\":\"assignee\",\"op\":\"any\",\"values\":[]}")).contains(n);

        // 이하늘 was told: 공동 담당자로 지정
        var groups = body(send(haneul, get("/api/v1/notifications?filter=all"), ""));
        var summaries = new ArrayList<String>();
        groups.forEach(g -> {
            if (g.get("workPackage").get("number").asLong() == n) g.get("items").forEach(i -> summaries.add(i.get("reason").asString() + " " + i.get("summary").asString()));
        });
        assertThat(summaries).anyMatch(x -> x.startsWith("assigned") && x.contains("공동 담당자로 지정했습니다"));
    }

    @Test
    void anAssigneeBoardShowsTheCardForEachPersonAndAMoveHandsOneOver() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        long n = create(admin, "보드에서 넘기기", ",\"assignees\":[\"" + JUNHO + "\",\"" + HANEUL + "\"]").get("number").asLong();
        var board = body(send(admin, post("/api/v1/projects/web-platform/boards"), "{\"name\":\"사람별\",\"kind\":\"assignee\"}").andExpect(status().isCreated()));
        var id = board.get("id").asString();
        try {
            String junhoCol = null, haneulCol = null, minjiCol = null;
            int seen = 0;
            for (var c : board.get("columns")) {
                var title = c.get("title").asString();
                if (title.equals("박준호")) junhoCol = c.get("id").asString();
                if (title.equals("이하늘")) haneulCol = c.get("id").asString();
                if (title.equals("최민지")) minjiCol = c.get("id").asString();
                for (var card : c.get("cards")) if (card.get("number").asLong() == n) seen++;
            }
            assertThat(seen).as("in both people's columns").isEqualTo(2);
            assertThat(minjiCol).isNotNull();
            var wp = body(send(admin, get("/api/v1/work-packages/" + n), ""));
            board = body(send(admin, post("/api/v1/boards/" + id + "/moves"), "{\"number\":" + n + ",\"fromColumnId\":\"" + haneulCol + "\",\"toColumnId\":\"" + minjiCol
                    + "\",\"lockVersion\":" + wp.get("lockVersion").asInt() + "}").andExpect(status().isOk()));
            assertThat(people(body(send(admin, get("/api/v1/work-packages/" + n), "")))).containsExactly("박준호", "최민지");
            assertThat(junhoCol).isNotNull();
        } finally {
            send(admin, delete("/api/v1/boards/" + id), "");
        }
    }

    @Test
    void copiesKeepThePeopleAndADeletedPersonLeavesEveryList() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        // a person made for this test, put on the project
        var user = body(send(admin, post("/api/v1/admin/users"), "{\"email\":\"co.assignee@openpms.local\",\"name\":\"임시 담당\"}").andExpect(status().isCreated()));
        var temp = user.get("id").asString();
        var role = "6d4333fb-e975-5213-8ebc-552cca26e1d4";
        send(admin, post("/api/v1/projects/web-platform/members"), "{\"principalIds\":[\"" + temp + "\"],\"roleIds\":[\"" + role + "\"]}").andExpect(status().isCreated());
        long n = create(admin, "복사와 삭제", ",\"assignees\":[{\"userId\":\"" + temp + "\",\"workHours\":3},{\"userId\":\"" + HANEUL + "\",\"workHours\":2}]")
                .get("number").asLong();

        var copy = body(send(admin, post("/api/v1/work-packages/" + n + "/copy"), "{}").andExpect(status().isCreated()));
        assertThat(people(copy)).containsExactly("임시 담당 3.0", "이하늘 2.0");

        send(admin, delete("/api/v1/admin/users/" + temp), "").andExpect(status().isNoContent());
        var after = body(send(admin, get("/api/v1/work-packages/" + n), ""));
        assertThat(people(after)).as("the next person takes over").containsExactly("이하늘 2.0");
        assertThat(after.get("assignee").get("name").asString()).isEqualTo("이하늘");
    }
}

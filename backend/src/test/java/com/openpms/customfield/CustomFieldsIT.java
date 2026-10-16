package com.openpms.customfield;

import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Custom fields over the API on the dev data set (fields of scripts/gen_dev_custom_fields.py). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CustomFieldsIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
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

    String fieldId(MockHttpSession s, String name) throws Exception {
        var body = mvc.perform(get("/api/v1/admin/custom-fields").session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var f : json.readTree(body)) if (f.get("name").asString().equals(name)) return f.get("id").asString();
        throw new AssertionError("no field " + name);
    }

    int lockVersion(MockHttpSession s, int number) throws Exception {
        var body = mvc.perform(get("/api/v1/work-packages/" + number).session(s)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("lockVersion").asInt();
    }

    @Test
    void onlyAdministratorsDefineFields() throws Exception {
        var member = login("junho.park@openpms.local");
        mvc.perform(get("/api/v1/admin/custom-fields").session(member)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/custom-fields").session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void valuesAreCheckedAgainstTheFieldAndOnlyActiveFieldsAreWritable() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var client = fieldId(admin, "고객사");
        var points = fieldId(admin, "스토리 포인트");
        var relnote = fieldId(admin, "릴리스 노트 필요");
        // #3 is a task in 웹 플랫폼 개편: 고객사 and 스토리 포인트 are on, 릴리스 노트 필요 (features and bugs) is not
        mvc.perform(get("/api/v1/work-packages/3").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.customValues['" + client + "'].text").value("한빛은행"))
                .andExpect(jsonPath("$.customValues['" + relnote + "']").doesNotExist());
        mvc.perform(patch("/api/v1/work-packages/3").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lockVersion(admin, 3) + ",\"customValues\":{\"" + relnote + "\":true}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("cf:" + relnote));
        mvc.perform(patch("/api/v1/work-packages/3").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lockVersion(admin, 3) + ",\"customValues\":{\"" + points + "\":2.5}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].message").value("스토리 포인트: 정수를 입력하세요."));
        mvc.perform(patch("/api/v1/work-packages/3").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lockVersion(admin, 3) + ",\"customValues\":{\"" + client + "\":\"" + java.util.UUID.randomUUID() + "\"}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].message").value("고객사: 없는 선택지입니다."));
        mvc.perform(patch("/api/v1/work-packages/3").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockVersion\":" + lockVersion(admin, 3) + ",\"customValues\":{\"" + points + "\":3}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customValues['" + points + "'].text").value("3"));
        mvc.perform(get("/api/v1/work-packages/3/activities").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[-1].details[?(@.label == '스토리 포인트')].to").value("3"));
    }

    @Test
    void listsFilterAndSortByCustomFields() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var client = fieldId(admin, "고객사");
        var spec = "{\"v\":1,\"filters\":[{\"field\":\"cf:" + client + "\",\"op\":\"any\"}],\"sort\":[{\"field\":\"cf:" + client + "\",\"dir\":\"asc\"}],"
                + "\"hierarchy\":false,\"includeSubprojects\":true}";
        var body = mvc.perform(post("/api/v1/work-packages/query").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"web-platform\",\"spec\":" + spec + ",\"pageSize\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rows = json.readTree(body).get("rows");
        var texts = new java.util.ArrayList<String>();
        for (var r : rows) texts.add(r.get("wp").get("customValues").get(client).get("text").asString());
        // option order: 사내, 한빛은행, 미래상사, 다온커머스 — #3 #4 are 한빛은행, #11 미래상사
        assertThat(texts).containsExactly("한빛은행", "한빛은행", "미래상사");
    }

    @Test
    void deletingAFieldClearsValuesWithoutTouchingItemTimes() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        // its own field (the other tests use the seeded ones), on every project, for #9's type
        var typeId = json.readTree(mvc.perform(get("/api/v1/work-packages/9").session(admin)).andReturn().getResponse().getContentAsString())
                .get("type").get("id").asString();
        var created = mvc.perform(post("/api/v1/admin/custom-fields").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"삭제할 필드\",\"format\":\"string\",\"isForAll\":true,\"isFilterable\":true,\"typeIds\":[\"" + typeId + "\"],\"options\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id = json.readTree(created).get("id").asString();
        mvc.perform(patch("/api/v1/work-packages/9").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lockVersion\":" + lockVersion(admin, 9) + ",\"customValues\":{\"" + id + "\":\"곧 지움\"}}")).andExpect(status().isOk());
        var before = db.select(WORK_PACKAGES.UPDATED_AT).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(9L)).fetchOne().value1();
        mvc.perform(delete("/api/v1/admin/custom-fields/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        var row = db.select(WORK_PACKAGES.UPDATED_AT, WORK_PACKAGES.CUSTOM_VALUES).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(9L)).fetchOne();
        assertThat(row.value1()).isEqualTo(before);
        assertThat(row.value2().data()).doesNotContain(id);
    }
}

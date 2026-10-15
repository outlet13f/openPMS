package com.openpms.itsm;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CatalogAdminIT {
    private static final String STEPS = "[{\"stepNo\":1,\"kind\":\"requester_manager\"},{\"stepNo\":2,\"kind\":\"group_lead\"}]";

    @Autowired WebApplicationContext context;
    ItsmFixture f;
    MockHttpSession admin, agent, requester;
    String desk;
    String base;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
        agent = f.login(ItsmFixture.AGENT);
        requester = f.login(ItsmFixture.REQUESTER);
        desk = f.newDesk(admin);
        base = "/api/v1/projects/" + desk + "/service-desk/catalog";
    }

    /** A list field for service requests, on for the desk (removed by the caller). */
    String newField(boolean enableOnDesk) throws Exception {
        var name = "장비 종류 " + UUID.randomUUID().toString().substring(0, 6);
        var id = f.body(f.send(admin, post("/api/v1/admin/custom-fields"), "{\"name\":\"" + name + "\",\"format\":\"list\",\"isForAll\":false,"
                + "\"options\":[{\"value\":\"노트북\"},{\"value\":\"데스크톱\"}],\"typeIds\":[\"" + ItsmIds.TYPE_SERVICE_REQUEST + "\"]}")
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        if (enableOnDesk) f.send(admin, put("/api/v1/projects/" + desk + "/custom-fields"), "{\"customFieldIds\":[\"" + id + "\"]}").andExpect(status().isOk());
        return id;
    }

    void dropField(String id) throws Exception {
        f.send(admin, delete("/api/v1/admin/custom-fields/" + id), "");
    }

    @Test void managerCreatesAnItemWithFieldsAndSteps() throws Exception {
        var field = newField(true);
        try {
            var category = f.body(f.send(admin, post(base + "/categories"), "{\"name\":\"하드웨어\",\"position\":1}").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("하드웨어")).andReturn()).get("id").asString();
            var item = f.body(f.send(admin, post(base + "/items"), "{\"name\":\"노트북 신청\",\"categoryId\":\"" + category + "\",\"descriptionHtml\":\"<p>새 노트북</p><script>x()</script>\","
                    + "\"defaultPriorityId\":\"" + ItsmFixture.NORMAL + "\",\"assignmentGroupId\":\"25629a29-9f0b-50a1-a812-f04018678b83\","
                    + "\"fields\":[{\"customFieldId\":\"" + field + "\",\"isRequired\":true}],\"steps\":" + STEPS + "}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.typeId").value(ItsmIds.TYPE_SERVICE_REQUEST.toString()))
                    .andExpect(jsonPath("$.isActive").value(true)).andReturn()).get("id").asString();
            f.send(admin, get(base), "").andExpect(status().isOk())
                    .andExpect(jsonPath("$.categories[0].id").value(category))
                    .andExpect(jsonPath("$.items[0].id").value(item))
                    .andExpect(jsonPath("$.items[0].categoryId").value(category))
                    .andExpect(jsonPath("$.items[0].descriptionHtml").value(Matchers.not(Matchers.containsString("script"))))
                    .andExpect(jsonPath("$.items[0].fields[0].customFieldId").value(field))
                    .andExpect(jsonPath("$.items[0].fields[0].isRequired").value(true))
                    .andExpect(jsonPath("$.items[0].steps[0].stepNo").value(1))
                    .andExpect(jsonPath("$.items[0].steps[0].kind").value("requester_manager"))
                    .andExpect(jsonPath("$.items[0].steps[1].stepNo").value(2))
                    .andExpect(jsonPath("$.items[0].steps[1].kind").value("group_lead"));
        } finally {
            dropField(field);
        }
    }

    @Test void agentsReadButCannotWrite() throws Exception {
        f.send(agent, get(base), "").andExpect(status().isOk());
        f.send(agent, post(base + "/items"), "{\"name\":\"x\"}").andExpect(status().isForbidden());
        f.send(agent, post(base + "/categories"), "{\"name\":\"x\"}").andExpect(status().isForbidden());
    }

    @Test void nonMembersSeeNothing() throws Exception {
        f.send(requester, get(base), "").andExpect(status().isNotFound());
        f.send(requester, post(base + "/items"), "{\"name\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test void rejectsBadInput() throws Exception {
        var field = newField(false);
        try {
            f.newItem(admin, desk, "VPN 계정", "[]");
            f.send(admin, post(base + "/items"), "{\"name\":\"VPN 계정\"}").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("name"));
            f.send(admin, post(base + "/items"), "{\"name\":\"\"}").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("name"));
            f.send(admin, post(base + "/items"), "{\"name\":\"유형\",\"typeId\":\"dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("typeId"));
            f.send(admin, post(base + "/items"), "{\"name\":\"필드\",\"fields\":[{\"customFieldId\":\"" + field + "\"}]}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("fields[0].customFieldId"));
            f.send(admin, post(base + "/items"), "{\"name\":\"단계\",\"steps\":[{\"stepNo\":10,\"kind\":\"group_lead\"}]}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("steps[0].stepNo"));
            f.send(admin, post(base + "/items"), "{\"name\":\"종류\",\"steps\":[{\"stepNo\":1,\"kind\":\"ceo\"}]}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("steps[0].kind"));
            f.send(admin, post(base + "/items"), "{\"name\":\"중복\",\"steps\":[{\"stepNo\":1,\"kind\":\"group_lead\"},{\"stepNo\":1,\"kind\":\"group_lead\"}]}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("steps[1].kind"));
            f.send(admin, post(base + "/items"), "{\"name\":\"분류\",\"categoryId\":\"" + UUID.randomUUID() + "\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("categoryId"));
            f.send(admin, post(base + "/items"), "{\"name\":\"그룹\",\"assignmentGroupId\":\"" + UUID.randomUUID() + "\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("assignmentGroupId"));
            f.send(admin, post(base + "/items"), "{\"name\":\"우선순위\",\"defaultPriorityId\":\"" + UUID.randomUUID() + "\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("defaultPriorityId"));
        } finally {
            dropField(field);
        }
    }

    @Test void patchReplacesListsOnlyWhenSent() throws Exception {
        var field = newField(true);
        try {
            var item = f.body(f.send(admin, post(base + "/items"), "{\"name\":\"폴더\",\"fields\":[{\"customFieldId\":\"" + field + "\"}],\"steps\":" + STEPS + "}")
                    .andExpect(status().isCreated()).andReturn()).get("id").asString();
            f.send(admin, patch(base + "/items/" + item), "{\"isActive\":false}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.isActive").value(false))
                    .andExpect(jsonPath("$.fields.length()").value(1))
                    .andExpect(jsonPath("$.steps.length()").value(2));
            f.send(admin, patch(base + "/items/" + item), "{\"steps\":[]}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.steps.length()").value(0))
                    .andExpect(jsonPath("$.fields.length()").value(1));
        } finally {
            dropField(field);
        }
    }

    @Test void deletingACategoryKeepsItsItems() throws Exception {
        var category = f.body(f.send(admin, post(base + "/categories"), "{\"name\":\"계정\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        var item = f.body(f.send(admin, post(base + "/items"), "{\"name\":\"계정 신청\",\"categoryId\":\"" + category + "\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        f.send(admin, delete(base + "/categories/" + category), "").andExpect(status().isNoContent());
        f.send(admin, get(base), "").andExpect(jsonPath("$.categories.length()").value(0))
                .andExpect(jsonPath("$.items[0].id").value(item))
                .andExpect(jsonPath("$.items[0].categoryId").doesNotExist());
        f.send(admin, delete(base + "/items/" + item), "").andExpect(status().isNoContent());
        f.send(admin, get(base), "").andExpect(jsonPath("$.items.length()").value(0));
    }
}

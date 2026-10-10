package com.openpms.wp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
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

/** S28 유형 양식 구성 over the API: checked forms, the default, the description template. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class TypeFormsIT {
    static final String BUG = "35b87fc8-00ab-537a-af55-db53bcfa5f78";
    static final String WEB = "/api/v1/projects/web-platform";

    @Autowired
    WebApplicationContext context;

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

    org.springframework.test.web.servlet.ResultActions patchType(MockHttpSession s, String body) throws Exception {
        return mvc.perform(patch("/api/v1/admin/types/" + BUG).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void onlyAdministratorsChangeAForm() throws Exception {
        patchType(login("junho.park@openpms.local"), "{\"form\":null}").andExpect(status().isForbidden());
    }

    @Test
    void badFormsAreRefused() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        patchType(admin, "{\"form\":{\"groups\":[{\"name\":\"a\",\"attributes\":[\"nope\"]}],\"hidden\":[]}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].message").value("알 수 없는 항목입니다: nope"));
        patchType(admin, "{\"form\":{\"groups\":[{\"name\":\"a\",\"attributes\":[\"type\"]}],\"hidden\":[\"type\"]}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].message").value("같은 항목이 두 번 있습니다."));
        patchType(admin, "{\"form\":{\"groups\":[{\"name\":\"a\",\"attributes\":[]},{\"name\":\" a \",\"attributes\":[]}],\"hidden\":[]}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].message").value("같은 이름의 그룹이 있습니다: a"));
        patchType(admin, "{\"form\":{\"groups\":[{\"name\":\"  \",\"attributes\":[]}],\"hidden\":[]}}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("form"));
    }

    @Test
    void aFormReachesTheOptionsAndTheTemplateTheNewItemForm() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        try {
            patchType(admin, """
                    {"form":{"groups":[{"name":"재현","attributes":["priority","version"]}],"hidden":["category"]},
                     "descriptionTemplateHtml":"<p>재현 절차</p><script>alert(1)</script>"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.form.groups[0].name").value("재현"))
                    .andExpect(jsonPath("$.form.hidden[0]").value("category"))
                    .andExpect(jsonPath("$.descriptionTemplateHtml").value(Matchers.not(Matchers.containsString("script"))));
            mvc.perform(get("/api/v1/work-packages/filters?projectId=web-platform").session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.typeForms['" + BUG + "'].groups[0].attributes[1]").value("version"))
                    // a type without a form gets the default
                    .andExpect(jsonPath("$.typeForms['dc1b8c74-6fb8-5e29-8c48-e633b98bdfbc'].groups[0].name").value("사람"));
            mvc.perform(get(WEB + "/work-packages/form?typeId=" + BUG).session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaults.descriptionHtml").value(Matchers.containsString("재현 절차")));
        } finally {
            patchType(admin, "{\"form\":null,\"descriptionTemplateHtml\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.form").doesNotExist());
        }
    }
}

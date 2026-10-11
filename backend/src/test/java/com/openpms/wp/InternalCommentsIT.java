package com.openpms.wp;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.PROJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
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

/** 내부 코멘트: on per project; permitted roles write and read them, everyone else never sees them (not even in the count). */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class InternalCommentsIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        db.deleteFrom(JOURNALS).where(JOURNALS.IS_INTERNAL.isTrue()).execute();
        db.update(PROJECTS).set(PROJECTS.INTERNAL_COMMENTS, false).where(PROJECTS.IDENTIFIER.eq("web-platform")).execute();
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void onlyPermittedRolesWriteAndReadInternalComments() throws Exception {
        var manager = login("seoyeon.kim@openpms.local"); // 프로젝트 관리자: may write and read them
        var member = login("haneul.lee@openpms.local"); // 멤버: may not
        var internal = "{\"bodyHtml\":\"<p>내부 메모</p>\",\"internal\":true}";
        send(manager, post("/api/v1/work-packages/3/comments"), internal).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/work-packages/3").session(manager)).andExpect(jsonPath("$._meta.allowedActions.commentInternal").value(false));

        send(manager, patch("/api/v1/projects/web-platform"), "{\"internalComments\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.internalComments").value(true));
        mvc.perform(get("/api/v1/work-packages/3").session(manager)).andExpect(jsonPath("$._meta.allowedActions.commentInternal").value(true));
        mvc.perform(get("/api/v1/work-packages/3").session(member)).andExpect(jsonPath("$._meta.allowedActions.commentInternal").value(false));
        send(manager, post("/api/v1/work-packages/3/comments"), internal).andExpect(status().isCreated()).andExpect(jsonPath("$.isInternal").value(true));
        send(member, post("/api/v1/work-packages/3/comments"), internal).andExpect(status().isForbidden());

        var forManager = mvc.perform(get("/api/v1/work-packages/3/activities").session(manager)).andReturn().getResponse().getContentAsString();
        var forMember = mvc.perform(get("/api/v1/work-packages/3/activities").session(member)).andReturn().getResponse().getContentAsString();
        assertThat(forManager).contains("내부 메모");
        assertThat(forMember).doesNotContain("내부 메모");
        // a plain comment still reaches everyone
        send(member, post("/api/v1/work-packages/3/comments"), "{\"bodyHtml\":\"<p>모두에게</p>\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.isInternal").value(false));
        db.deleteFrom(JOURNALS).where(JOURNALS.NOTES_TEXT.eq("모두에게")).execute();
    }
}

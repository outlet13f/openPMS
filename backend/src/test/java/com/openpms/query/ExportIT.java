package com.openpms.query;

import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static com.openpms.db.Tables.WORK_PACKAGE_SHARES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.jooq.DSLContext;
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

/** S12 내보내기: formula text is defused, the count tells what the file leaves out, a share alone does not export. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class ExportIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;

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

    ResultActions post_(MockHttpSession s, String path, String body) throws Exception {
        return mvc.perform(post("/api/v1" + path).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static String spec(String text) {
        return "{\"filters\":[],\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"columns\":[\"subject\"],\"text\":" + (text == null ? "null" : "\"" + text + "\"") + "}";
    }

    @Test
    void textASpreadsheetWouldRunIsDefused() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var old = db.select(WORK_PACKAGES.SUBJECT).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(15L)).fetchOne(WORK_PACKAGES.SUBJECT);
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.SUBJECT, "=1+1").where(WORK_PACKAGES.NUMBER.eq(15L)).execute();
        try {
            var csv = post_(admin, "/work-packages/export", "{\"spec\":" + spec("#15") + ",\"format\":\"csv\",\"columns\":[\"subject\",\"number\"],\"title\":\"t\"}")
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(csv).contains("'=1+1,15").doesNotContain("\n=1+1");
            // the list's work columns (2bb76a8) export too, as numbers
            var work = post_(admin, "/work-packages/export", "{\"spec\":" + spec("#3") + ",\"format\":\"csv\",\"columns\":[\"number\",\"work\",\"remainingWork\"],\"title\":\"t\"}")
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(work).contains("번호,작업량(시간),남은 작업(시간)");
        } finally {
            db.update(WORK_PACKAGES).set(WORK_PACKAGES.SUBJECT, old).where(WORK_PACKAGES.NUMBER.eq(15L)).execute();
        }
    }

    @Test
    void anItemSeenOnlyThroughAShareIsCountedAsLeftOutAndNotExported() throws Exception {
        var doyoonId = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("doyoon.han@openpms.local")).fetchOne(USERS.ID);
        var wp4 = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(4L)).fetchOne(WORK_PACKAGES.ID);
        db.insertInto(WORK_PACKAGE_SHARES).set(WORK_PACKAGE_SHARES.WORK_PACKAGE_ID, wp4).set(WORK_PACKAGE_SHARES.USER_ID, doyoonId)
                .set(WORK_PACKAGE_SHARES.ROLE, "view").execute();
        try {
            var doyoon = login("doyoon.han@openpms.local");
            post_(doyoon, "/work-packages/export/count", "{\"spec\":" + spec("#4") + "}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.excluded").value(1));
            post_(doyoon, "/work-packages/export", "{\"spec\":" + spec("#4") + ",\"format\":\"csv\",\"columns\":[\"subject\"],\"title\":\"t\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.title").value("내보낼 수 있는 작업이 없습니다."));
            // the project's own members export it as usual
            post_(login("seoyeon.kim@openpms.local"), "/work-packages/export/count", "{\"spec\":" + spec("#4") + "}")
                    .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.excluded").value(0));
        } finally {
            db.deleteFrom(WORK_PACKAGE_SHARES).where(WORK_PACKAGE_SHARES.WORK_PACKAGE_ID.eq(wp4), WORK_PACKAGE_SHARES.USER_ID.eq(doyoonId)).execute();
        }
    }
}

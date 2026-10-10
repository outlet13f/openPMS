package com.openpms.misc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpms.common.Dates;
import java.time.LocalDate;
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

/** 관리 · 달력과 날짜 · 날짜 형식: everyone reads it, administrators change it, the server writes dates that way too. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class DisplaySettingsIT {
    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MockHttpSession login(String email) throws Exception {
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        return s;
    }

    @Test
    void everyoneReadsItAdministratorsChangeItAndServerTextFollows() throws Exception {
        var member = login("haneul.lee@openpms.local");
        mvc.perform(get("/api/v1/settings/display").session(member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dateFormat").value("default")).andExpect(jsonPath("$.timeFormat").value("default"))
                .andExpect(jsonPath("$.weekStart").value("7"));
        mvc.perform(put("/api/v1/admin/settings/display").session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateFormat\":\"YYYY-MM-DD\",\"timeFormat\":\"12h\",\"weekStart\":\"1\"}")).andExpect(status().isForbidden());

        var admin = login("seoyeon.kim@openpms.local");
        try {
            mvc.perform(put("/api/v1/admin/settings/display").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateFormat\":\"dd/mm\",\"timeFormat\":\"12h\",\"weekStart\":\"1\"}"))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("dateFormat"));
            mvc.perform(put("/api/v1/admin/settings/display").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateFormat\":\"YYYY-MM-DD\",\"timeFormat\":\"12h\",\"weekStart\":\"1\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.weekStart").value("1"));
            mvc.perform(get("/api/v1/settings/display").session(member)).andExpect(jsonPath("$.dateFormat").value("YYYY-MM-DD"))
                    .andExpect(jsonPath("$.timeFormat").value("12h"));
            assertThat(Dates.format(LocalDate.of(2026, 10, 21))).isEqualTo("2026-10-21");
            mvc.perform(put("/api/v1/admin/settings/display").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateFormat\":\"YYYY년 M월 D일\",\"timeFormat\":\"24h\",\"weekStart\":\"default\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.weekStart").value("default"));
            assertThat(Dates.format(LocalDate.of(2026, 1, 5))).isEqualTo("2026년 1월 5일");
        } finally {
            // the test database and the format are shared with the other test classes
            mvc.perform(put("/api/v1/admin/settings/display").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateFormat\":\"default\",\"timeFormat\":\"default\",\"weekStart\":\"7\"}")).andExpect(status().isOk());
        }
        assertThat(Dates.format(LocalDate.of(2026, 10, 21))).isEqualTo("2026.10.21");
    }
}

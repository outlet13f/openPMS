package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A failed sign-in takes as long whether or not the address has an account (or a password): otherwise the answer time tells
 * which company addresses exist. Medians of a few tries each, with a wide margin (the password hash is ~10x the rest).
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class LoginTimingIT {
    static final String KNOWN = "haneul.lee@openpms.local";
    static final int TRIES = 7;

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
        db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL).where(USERS.EMAIL.eq(KNOWN)).execute();
    }

    long failedSignInNanos(String email) throws Exception {
        // never let the known account reach the lockout: this test is about time, not about locking
        db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL).where(USERS.EMAIL.eq(KNOWN)).execute();
        long start = System.nanoTime();
        mvc.perform(post("/api/v1/auth/login").session(new MockHttpSession()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"not-the-password\"}")).andExpect(status().isUnauthorized());
        return System.nanoTime() - start;
    }

    long median(String email) throws Exception {
        var times = new long[TRIES];
        for (int i = 0; i < TRIES; i++) times[i] = failedSignInNanos(email);
        Arrays.sort(times);
        return times[TRIES / 2];
    }

    @Test
    void anUnknownAddressTakesAsLongAsAWrongPassword() throws Exception {
        failedSignInNanos(KNOWN); // warm up
        long known = median(KNOWN);
        long unknown = median("nobody-" + System.nanoTime() + "@openpms.local");
        assertThat(unknown).as("unknown %d ms vs known %d ms", unknown / 1_000_000, known / 1_000_000).isGreaterThan(known / 2);
    }
}

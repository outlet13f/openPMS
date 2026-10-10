package com.openpms.auth;

import static com.openpms.db.Tables.USERS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
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

/** S01 one-time links: only the newest works, a used one closes the rest, an accepted invitation signs in. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class AuthLinksIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    Tokens tokens;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    UUID user(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
    }

    @Test
    void aPasswordResetClosesTheOtherResetLinks() throws Exception {
        var id = user("haneul.lee@openpms.local");
        // other tests in the run sign in as this user: put the password back afterwards
        var hash = db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(id)).fetchOne(USERS.PASSWORD_HASH);
        try {
            resetAndSignIn(id);
        } finally {
            db.update(USERS).set(USERS.PASSWORD_HASH, hash).where(USERS.ID.eq(id)).execute();
        }
    }

    void resetAndSignIn(UUID id) throws Exception {
        var older = tokens.issue(id, "password_reset", Duration.ofHours(1), null);
        var newer = tokens.issue(id, "password_reset", Duration.ofHours(1), null);
        mvc.perform(post("/api/v1/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + newer + "\",\"newPassword\":\"short\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        mvc.perform(post("/api/v1/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + newer + "\",\"newPassword\":\"openpms!-reset\"}")).andExpect(status().isNoContent());
        for (var t : new String[] {older, newer}) {
            mvc.perform(post("/api/v1/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + t + "\",\"newPassword\":\"openpms!-again\"}")).andExpect(status().isGone());
        }
        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"haneul.lee@openpms.local\",\"password\":\"openpms!-reset\"}")).andExpect(status().isOk());
    }

    @Test
    void anInvitationIsAcceptedOnceAndSignsIn() throws Exception {
        var admin = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"초대 확인\",\"email\":\"invite.it@openpms.local\",\"isAdmin\":false}")).andExpect(status().is2xxSuccessful());
        var id = user("invite.it@openpms.local");
        // a resend replaces the mailed link: the one issued here stands for the newest
        var stale = tokens.issue(id, "invitation", Duration.ofHours(1), null);
        tokens.revokeAll(id, "invitation");
        var link = tokens.issue(id, "invitation", Duration.ofHours(1), null);
        mvc.perform(get("/api/v1/auth/invitations/" + stale)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/auth/invitations/" + link)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("invite.it@openpms.local"));

        var s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/invitations/" + link + "/accept").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"초대 확인\",\"password\":\"welcome-2026!\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("invite.it@openpms.local"));
        mvc.perform(post("/api/v1/auth/invitations/" + link + "/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"다시\",\"password\":\"welcome-2026!\"}")).andExpect(status().isGone());
    }
}

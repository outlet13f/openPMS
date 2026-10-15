package com.openpms.itsm;

import static com.openpms.db.Tables.USERS;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.openpms.auth.OidcLogin;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
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
class ManagersIT {
    private static final String ALEX = "alex.kim@openpms.local";
    private static final String FORMER = "former.staff@openpms.local";
    private static final UUID FORMER_ID = UUID.fromString("e8ef8f7e-30a6-56b9-9ad9-68ff5cbbdf73");

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired OidcLogin oidc;
    ItsmFixture f;
    MockHttpSession admin;

    @BeforeEach void setUp() throws Exception {
        f = new ItsmFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(ItsmFixture.ADMIN);
    }

    private UUID idOf(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
    }

    private UUID managerOf(UUID user) {
        return db.select(USERS.MANAGER_ID).from(USERS).where(USERS.ID.eq(user)).fetchOne(USERS.MANAGER_ID);
    }

    private void clearManager(UUID user) {
        db.update(USERS).setNull(USERS.MANAGER_ID).where(USERS.ID.eq(user)).execute();
    }

    @Test void adminSetsAndClearsAManager() throws Exception {
        var alex = idOf(ALEX);
        try {
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + alex + "\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.manager.id").value(alex.toString()));
            f.mvc.perform(get("/api/v1/admin/users").session(admin).param("q", "minji")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].manager.name").isNotEmpty());
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":null}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.manager").value(Matchers.nullValue()));
            assertThat(managerOf(ItsmFixture.MINJI)).isNull();
        } finally {
            clearManager(ItsmFixture.MINJI);
        }
    }

    @Test void aManagerCannotBeSelfOrInactive() throws Exception {
        try {
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + ItsmFixture.MINJI + "\"}")
                    .andExpect(status().isUnprocessableEntity()).andExpect(content().string(Matchers.containsString("자기 자신을 관리자로 지정할 수 없습니다.")));
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + FORMER_ID + "\"}").andExpect(status().isUnprocessableEntity());
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isUnprocessableEntity());
            assertThat(managerOf(ItsmFixture.MINJI)).isNull();
        } finally {
            clearManager(ItsmFixture.MINJI);
        }
    }

    @Test void noManagerFilter() throws Exception {
        var alex = idOf(ALEX);
        try {
            f.send(admin, patch("/api/v1/admin/users/" + ItsmFixture.MINJI), "{\"managerId\":\"" + alex + "\"}").andExpect(status().isOk());
            f.mvc.perform(get("/api/v1/admin/users").session(admin).param("noManager", "true")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id=='" + ItsmFixture.MINJI + "')]").isEmpty())
                    .andExpect(jsonPath("$[?(@.id=='" + FORMER_ID + "')]").isEmpty()) // locked: not an active person without a manager
                    .andExpect(jsonPath("$[?(@.id=='" + alex + "')]").isNotEmpty());
        } finally {
            clearManager(ItsmFixture.MINJI);
        }
    }

    @Test void groupLead() throws Exception {
        var created = f.body(f.send(admin, post("/api/v1/admin/groups"), "{\"name\":\"책임자 시험 " + UUID.randomUUID().toString().substring(0, 6) + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lead").value(Matchers.nullValue())).andReturn());
        var id = created.get("id").asString();
        try {
            f.send(admin, patch("/api/v1/admin/groups/" + id), "{\"leadUserId\":\"" + ItsmFixture.MINJI + "\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.lead.id").value(ItsmFixture.MINJI.toString()));
            f.mvc.perform(get("/api/v1/admin/groups").session(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id=='" + id + "')].lead.id").value(Matchers.contains(ItsmFixture.MINJI.toString())));
            f.send(admin, patch("/api/v1/admin/groups/" + id), "{\"leadUserId\":\"" + FORMER_ID + "\"}").andExpect(status().isUnprocessableEntity());
            f.send(admin, patch("/api/v1/admin/groups/" + id), "{\"leadUserId\":null}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.lead").value(Matchers.nullValue()));
            f.send(admin, post("/api/v1/admin/groups"), "{\"name\":\"책임자 시험 둘 " + UUID.randomUUID().toString().substring(0, 6) + "\",\"leadUserId\":\"" + ItsmFixture.JUNHO + "\"}")
                    .andExpect(status().isCreated()).andDo(r -> {
                        var second = f.body(r).get("id").asString();
                        f.send(admin, delete("/api/v1/admin/groups/" + second), "");
                    });
        } finally {
            f.send(admin, delete("/api/v1/admin/groups/" + id), "");
        }
    }

    @Test void ssoClaimSyncsTheManager() {
        var alex = idOf(ALEX);
        try {
            oidc.syncManager(ItsmFixture.MINJI, ALEX);
            assertThat(managerOf(ItsmFixture.MINJI)).isEqualTo(alex);
            oidc.syncManager(ItsmFixture.MINJI, "nobody@openpms.local");
            assertThat(managerOf(ItsmFixture.MINJI)).isEqualTo(alex);
            oidc.syncManager(ItsmFixture.MINJI, ItsmFixture.REQUESTER);
            assertThat(managerOf(ItsmFixture.MINJI)).isEqualTo(alex);
            oidc.syncManager(ItsmFixture.MINJI, FORMER); // locked: not a manager
            assertThat(managerOf(ItsmFixture.MINJI)).isEqualTo(alex);
        } finally {
            clearManager(ItsmFixture.MINJI);
        }
    }
}

package com.openpms.admin;

import static com.openpms.db.Tables.GLOBAL_ROLE_ASSIGNMENTS;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.ROLES;
import static com.openpms.db.Tables.USERS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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

/** S31·S35 전역 역할: an administrator gives a person a global role, and its rights apply at once; nobody else hands them out. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class GlobalRolesIT {
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
        db.deleteFrom(GLOBAL_ROLE_ASSIGNMENTS).execute();
        db.deleteFrom(PROJECTS).where(PROJECTS.IDENTIFIER.eq("by-global-role")).execute();
        db.deleteFrom(ROLES).where(ROLES.NAME.eq("사용자 관리자(시험)")).execute();
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

    UUID user(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
    }

    @Test
    void anAdministratorGivesAGlobalRoleAndItsRightsApply() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var creator = db.select(ROLES.ID).from(ROLES).where(ROLES.SCOPE.eq("global"), ROLES.NAME.eq("프로젝트 생성자")).fetchOne(ROLES.ID);
        var projectRole = db.select(ROLES.ID).from(ROLES).where(ROLES.SCOPE.eq("project")).limit(1).fetchOne(ROLES.ID);
        var haneul = user("haneul.lee@openpms.local");
        var project = "{\"name\":\"전역 역할로 만든 프로젝트\",\"identifier\":\"by-global-role\"}";

        var member = login("haneul.lee@openpms.local");
        send(member, post("/api/v1/projects"), project).andExpect(status().isForbidden());

        send(admin, put("/api/v1/admin/users/" + haneul + "/global-roles"), "{\"roleIds\":[\"" + projectRole + "\"]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("roleIds"));
        send(admin, put("/api/v1/admin/users/" + haneul + "/global-roles"), "{\"roleIds\":[\"" + creator + "\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.globalRoles[0].name").value("프로젝트 생성자"));
        mvc.perform(get("/api/v1/auth/me").session(member)).andExpect(jsonPath("$.globalPermissions[0]").value("global.create_projects"));
        send(member, post("/api/v1/projects"), project).andExpect(status().isCreated());

        // a user manager who is not an administrator cannot hand out global roles (not even to themselves)
        var managerRole = send(admin, post("/api/v1/admin/roles"), "{\"name\":\"사용자 관리자(시험)\",\"scope\":\"global\",\"permissions\":[\"global.manage_users\"]}")
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        var doyoon = user("doyoon.han@openpms.local");
        send(admin, put("/api/v1/admin/users/" + doyoon + "/global-roles"), "{\"roleIds\":[\"" + managerRole + "\"]}").andExpect(status().isOk());
        var manager = login("doyoon.han@openpms.local");
        mvc.perform(get("/api/v1/admin/users").session(manager)).andExpect(status().isOk());
        send(manager, put("/api/v1/admin/users/" + doyoon + "/global-roles"), "{\"roleIds\":[\"" + managerRole + "\",\"" + creator + "\"]}")
                .andExpect(status().isForbidden());

        // taking it away takes the right away
        send(admin, put("/api/v1/admin/users/" + haneul + "/global-roles"), "{\"roleIds\":[]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.globalRoles.length()").value(0));
        send(member, delete("/api/v1/projects/by-global-role"), "{\"confirmIdentifier\":\"by-global-role\"}").andExpect(status().isForbidden());
        send(member, post("/api/v1/projects"), "{\"name\":\"다시\",\"identifier\":\"by-global-role-2\"}").andExpect(status().isForbidden());
    }

    @Test
    void aUserManagerWhoIsNotAnAdministratorCannotLockRenameOrDeleteAnAdministrator() throws Exception {
        var admin = login("seoyeon.kim@openpms.local");
        var haneul = user("haneul.lee@openpms.local");
        // a second active administrator, so the "last administrator" rule does not hide the missing check
        var email = "second-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@openpms.local";
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"둘째 관리자\",\"email\":\"" + email + "\",\"isAdmin\":true,\"method\":\"temporaryPassword\"}")
                .andExpect(status().isCreated());
        var secondAdmin = user(email);
        var managerRole = send(admin, post("/api/v1/admin/roles"), "{\"name\":\"사용자 관리자(시험)\",\"scope\":\"global\",\"permissions\":[\"global.manage_users\"]}")
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        send(admin, put("/api/v1/admin/users/" + user("doyoon.han@openpms.local") + "/global-roles"), "{\"roleIds\":[\"" + managerRole + "\"]}").andExpect(status().isOk());
        var manager = login("doyoon.han@openpms.local");
        var refused = "관리자 계정은 관리자만 다룰 수 있습니다.";
        try {
            send(manager, patch("/api/v1/admin/users/" + secondAdmin), "{\"status\":\"locked\"}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value(refused));
            send(manager, patch("/api/v1/admin/users/" + secondAdmin), "{\"name\":\"바뀐 이름\"}").andExpect(status().isForbidden());
            send(manager, delete("/api/v1/admin/users/" + secondAdmin), "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
            // an ordinary account stays theirs to manage
            send(manager, patch("/api/v1/admin/users/" + haneul), "{\"status\":\"locked\"}").andExpect(status().isOk());
            send(manager, patch("/api/v1/admin/users/" + haneul), "{\"status\":\"active\"}").andExpect(status().isOk());
        } finally {
            // the second administrator was only for this test
            db.deleteFrom(USERS).where(USERS.ID.eq(secondAdmin)).execute();
        }
    }
}

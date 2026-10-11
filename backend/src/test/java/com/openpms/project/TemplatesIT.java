package com.openpms.project;

import static com.openpms.db.Tables.BOARDS;
import static com.openpms.db.Tables.CATEGORIES;
import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.MEMBER_ROLES;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.PROJECT_TYPES;
import static com.openpms.db.Tables.QUERIES;
import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.ROLES;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.VERSIONS;
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

import java.util.HashSet;
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

/** S04 템플릿에서 프로젝트 만들기: a project marked as template is offered, and a new project takes over what was asked for. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class TemplatesIT {
    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    MockMvc mvc;
    MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = login("seoyeon.kim@openpms.local");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (var id : new String[] {"from-template", "from-template-bare", "copied-project"}) {
            if (db.fetchExists(PROJECTS, PROJECTS.IDENTIFIER.eq(id)))
                send(admin, delete("/api/v1/projects/" + id), "{\"confirmIdentifier\":\"" + id + "\"}").andExpect(status().isNoContent());
        }
        db.update(PROJECTS).set(PROJECTS.IS_TEMPLATE, false).where(PROJECTS.IDENTIFIER.eq("web-platform")).execute();
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

    UUID id(String identifier) {
        return db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq(identifier)).fetchOne(PROJECTS.ID);
    }

    @Test
    void aTemplateIsOfferedAndANewProjectTakesOverWhatWasAskedFor() throws Exception {
        var template = id("web-platform");
        send(admin, post("/api/v1/projects"), "{\"name\":\"x\",\"identifier\":\"from-template\",\"templateId\":\"web-platform\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("template"));
        send(admin, patch("/api/v1/projects/web-platform"), "{\"isTemplate\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.isTemplate").value(true));
        mvc.perform(get("/api/v1/projects").param("filter", "templates").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].identifier").value("web-platform"));

        int notificationsBefore = db.fetchCount(NOTIFICATIONS);
        send(admin, post("/api/v1/projects"), "{\"name\":\"템플릿으로 만든 프로젝트\",\"identifier\":\"from-template\",\"templateId\":\"web-platform\","
                + "\"copy\":[\"members\",\"versions\",\"categories\",\"workPackages\",\"boards\",\"queries\"]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.isTemplate").value(false));
        var made = id("from-template");
        var t = db.selectFrom(PROJECTS).where(PROJECTS.ID.eq(template)).fetchOne();
        var p = db.selectFrom(PROJECTS).where(PROJECTS.ID.eq(made)).fetchOne();
        assertThat(p.getEnabledModules()).containsExactlyInAnyOrder(t.getEnabledModules());
        assertThat(p.getDescriptionHtml()).isEqualTo(t.getDescriptionHtml());
        assertThat(new HashSet<>(db.select(PROJECT_TYPES.TYPE_ID).from(PROJECT_TYPES).where(PROJECT_TYPES.PROJECT_ID.eq(made)).fetch(PROJECT_TYPES.TYPE_ID)))
                .isEqualTo(new HashSet<>(db.select(PROJECT_TYPES.TYPE_ID).from(PROJECT_TYPES).where(PROJECT_TYPES.PROJECT_ID.eq(template)).fetch(PROJECT_TYPES.TYPE_ID)));
        assertThat(db.fetchCount(MEMBERS, MEMBERS.PROJECT_ID.eq(made))).isEqualTo(db.fetchCount(MEMBERS, MEMBERS.PROJECT_ID.eq(template)));
        assertThat(db.fetchCount(VERSIONS, VERSIONS.PROJECT_ID.eq(made))).isEqualTo(db.fetchCount(VERSIONS, VERSIONS.PROJECT_ID.eq(template)));
        assertThat(db.fetchCount(CATEGORIES, CATEGORIES.PROJECT_ID.eq(made))).isEqualTo(db.fetchCount(CATEGORIES, CATEGORIES.PROJECT_ID.eq(template)));

        // work packages: same count, the hierarchy and the relations among them inside the new project, versions point at the copies
        var copies = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(made)).fetch();
        assertThat(copies).hasSize(db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(template)));
        var copyIds = copies.stream().map(w -> w.getId()).toList();
        assertThat(copies.stream().filter(w -> w.getParentId() != null)).isNotEmpty().allMatch(w -> copyIds.contains(w.getParentId()));
        var versions = db.select(VERSIONS.ID).from(VERSIONS).where(VERSIONS.PROJECT_ID.eq(made)).fetch(VERSIONS.ID);
        assertThat(copies.stream().filter(w -> w.getVersionId() != null)).isNotEmpty().allMatch(w -> versions.contains(w.getVersionId()));
        var templateIds = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(template)).fetch(WORK_PACKAGES.ID);
        assertThat(db.fetchCount(RELATIONS, RELATIONS.FROM_ID.in(copyIds), RELATIONS.TO_ID.in(copyIds)))
                .isPositive().isEqualTo(db.fetchCount(RELATIONS, RELATIONS.FROM_ID.in(templateIds), RELATIONS.TO_ID.in(templateIds)));
        assertThat(db.fetchCount(JOURNALS, JOURNALS.WORK_PACKAGE_ID.in(copyIds))).isEqualTo(copies.size());
        assertThat(db.fetchCount(NOTIFICATIONS)).as("copying a template notifies nobody").isEqualTo(notificationsBefore);
        assertThat(db.fetchCount(BOARDS, BOARDS.PROJECT_ID.eq(made))).isEqualTo(db.fetchCount(BOARDS, BOARDS.PROJECT_ID.eq(template), BOARDS.KIND.ne("subproject")));
        assertThat(db.fetchCount(QUERIES, QUERIES.PROJECT_ID.eq(made)))
                .isEqualTo(db.fetchCount(QUERIES, QUERIES.PROJECT_ID.eq(template), QUERIES.IS_PUBLIC.isTrue()) + mine(template));
        // the new project works like any other
        mvc.perform(get("/api/v1/projects/from-template/members").session(admin)).andExpect(status().isOk());

        // nothing optional: the modules and types still come along, the rest does not
        send(admin, post("/api/v1/projects"), "{\"name\":\"빈 복사\",\"identifier\":\"from-template-bare\",\"templateId\":\"web-platform\",\"copy\":[]}")
                .andExpect(status().isCreated());
        var bare = id("from-template-bare");
        assertThat(db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(bare))).isZero();
        assertThat(db.fetchCount(MEMBERS, MEMBERS.PROJECT_ID.eq(bare))).isEqualTo(1);
        assertThat(db.fetchCount(PROJECT_TYPES, PROJECT_TYPES.PROJECT_ID.eq(bare))).isEqualTo(db.fetchCount(PROJECT_TYPES, PROJECT_TYPES.PROJECT_ID.eq(template)));
        send(admin, post("/api/v1/projects"), "{\"name\":\"x\",\"identifier\":\"from-template-x\",\"templateId\":\"web-platform\",\"copy\":[\"attachments\"]}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anyProjectTheyMayEditCanBeCopied() throws Exception {
        var template = id("web-platform");
        // not a template: copying needs the right to edit it
        send(login("haneul.lee@openpms.local"), post("/api/v1/projects/web-platform/copy"), "{\"name\":\"x\",\"identifier\":\"copied-project\"}")
                .andExpect(status().isForbidden());
        send(admin, post("/api/v1/projects/web-platform/copy"), "{\"name\":\"웹 플랫폼 (사본)\",\"identifier\":\"copied-project\",\"copy\":[\"workPackages\",\"versions\"]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("웹 플랫폼 (사본)")).andExpect(jsonPath("$.isTemplate").value(false));
        var made = id("copied-project");
        assertThat(db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(made))).isEqualTo(db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(template)));
        assertThat(db.fetchCount(MEMBERS, MEMBERS.PROJECT_ID.eq(made))).isEqualTo(1); // members were not ticked
    }

    /** The template's private views of the person copying (they come along too). */
    int mine(UUID template) {
        var me = db.select(com.openpms.db.Tables.USERS.ID).from(com.openpms.db.Tables.USERS).where(com.openpms.db.Tables.USERS.EMAIL.eq("seoyeon.kim@openpms.local"))
                .fetchOne(com.openpms.db.Tables.USERS.ID);
        return db.fetchCount(QUERIES, QUERIES.PROJECT_ID.eq(template), QUERIES.IS_PUBLIC.isFalse(), QUERIES.OWNER_ID.eq(me));
    }

    @Test
    void whatTheCopyCannotKeepIsLeftOutAndWhatItCopiedIsPointedAt() throws Exception {
        var template = id("web-platform");
        var version = db.select(VERSIONS.ID).from(VERSIONS).where(VERSIONS.PROJECT_ID.eq(template)).limit(1).fetchOne(VERSIONS.ID);
        var view = json.readTree(send(admin, post("/api/v1/queries"), "{\"projectId\":\"web-platform\",\"name\":\"이 버전의 일\",\"isPublic\":true,\"view\":\"table\","
                + "\"spec\":{\"v\":1,\"filters\":[{\"field\":\"version\",\"op\":\"eq\",\"values\":[\"" + version + "\"]}],\"columns\":[\"subject\"],\"sort\":[],"
                + "\"hierarchy\":false,\"sums\":false,\"includeSubprojects\":false}}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        try {
            // without members: assignees and responsibles who are not in the new project are cleared, versions point at the copies
            send(admin, post("/api/v1/projects/web-platform/copy"), "{\"name\":\"멤버 없는 사본\",\"identifier\":\"copied-project\",\"copy\":[\"workPackages\",\"versions\",\"queries\"]}")
                    .andExpect(status().isCreated());
            var made = id("copied-project");
            var me = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("seoyeon.kim@openpms.local")).fetchOne(USERS.ID);
            assertThat(db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(template), WORK_PACKAGES.ASSIGNEE_ID.ne(me)).fetch()).as("the template has other assignees").isNotEmpty();
            for (var w : db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(made)).fetch()) {
                assertThat(w.getAssigneeId() == null || w.getAssigneeId().equals(me)).as("#" + w.getNumber() + " assignee").isTrue();
                assertThat(w.getResponsibleId() == null || w.getResponsibleId().equals(me)).as("#" + w.getNumber() + " responsible").isTrue();
            }
            var copiedVersion = db.select(VERSIONS.ID).from(VERSIONS).where(VERSIONS.PROJECT_ID.eq(made), VERSIONS.NAME.eq(
                    db.select(VERSIONS.NAME).from(VERSIONS).where(VERSIONS.ID.eq(version)).fetchOne(VERSIONS.NAME))).fetchOne(VERSIONS.ID);
            var spec = db.select(QUERIES.SPEC).from(QUERIES).where(QUERIES.PROJECT_ID.eq(made), QUERIES.NAME.eq("이 버전의 일")).fetchOne(QUERIES.SPEC).data();
            assertThat(spec).contains(copiedVersion.toString()).doesNotContain(version.toString());
            send(admin, delete("/api/v1/projects/copied-project"), "{\"confirmIdentifier\":\"copied-project\"}").andExpect(status().isNoContent());

            // with members: a group member comes along, and the creator keeps the manager role plus what the template gave them
            var grouped = db.select(PROJECTS.IDENTIFIER, PROJECTS.ID).from(MEMBERS).join(PROJECTS).on(PROJECTS.ID.eq(MEMBERS.PROJECT_ID))
                    .where(MEMBERS.GROUP_ID.isNotNull()).limit(1).fetchOne();
            send(admin, post("/api/v1/projects/" + grouped.value1() + "/copy"), "{\"name\":\"그룹 있는 사본\",\"identifier\":\"copied-project\",\"copy\":[\"members\"]}")
                    .andExpect(status().isCreated());
            made = id("copied-project");
            assertThat(db.fetchCount(MEMBERS, MEMBERS.PROJECT_ID.eq(made), MEMBERS.GROUP_ID.isNotNull()))
                    .isEqualTo(db.fetchCount(MEMBERS, MEMBERS.PROJECT_ID.eq(grouped.value2()), MEMBERS.GROUP_ID.isNotNull())).isPositive();
            var myRolesThere = db.select(MEMBER_ROLES.ROLE_ID).from(MEMBER_ROLES).join(MEMBERS).on(MEMBERS.ID.eq(MEMBER_ROLES.MEMBER_ID))
                    .where(MEMBERS.PROJECT_ID.eq(grouped.value2()), MEMBERS.USER_ID.eq(me)).fetch(MEMBER_ROLES.ROLE_ID);
            var myRolesHere = db.select(MEMBER_ROLES.ROLE_ID).from(MEMBER_ROLES).join(MEMBERS).on(MEMBERS.ID.eq(MEMBER_ROLES.MEMBER_ID))
                    .where(MEMBERS.PROJECT_ID.eq(made), MEMBERS.USER_ID.eq(me)).fetch(MEMBER_ROLES.ROLE_ID);
            var manager = db.select(ROLES.ID).from(ROLES).where(ROLES.NAME.eq("프로젝트 관리자")).fetchOne(ROLES.ID);
            assertThat(myRolesHere).contains(manager).containsAll(myRolesThere);
        } finally {
            send(admin, delete("/api/v1/queries/" + view.get("id").asString()), "").andExpect(status().is2xxSuccessful());
        }
    }
}

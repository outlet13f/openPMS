package com.openpms.project;

import static com.openpms.db.Tables.BOARDS;
import static com.openpms.db.Tables.BOARD_CARDS;
import static com.openpms.db.Tables.BOARD_COLUMNS;
import static com.openpms.db.Tables.CATEGORIES;
import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.MEMBER_ROLES;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.PROJECT_CUSTOM_FIELDS;
import static com.openpms.db.Tables.PROJECT_TYPES;
import static com.openpms.db.Tables.QUERIES;
import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.USER_GROUP_MEMBERS;
import static com.openpms.db.Tables.VERSIONS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.core.Ctx;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.wp.Journals;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;

/**
 * 템플릿에서 프로젝트 만들기 (S04): what a new project takes over from a template. Always: modules, work package types,
 * active custom fields, project attribute values and (when none was typed) the description. On request: members,
 * versions, categories, work packages (hierarchy and relations among them, a "created" journal each, no notifications),
 * boards and the template's public saved views. Ids of copied versions and categories are carried over in view and board
 * filters. People who are not members of the new project are left out of assignee, responsible and board columns.
 */
@Service
public class ProjectCopy {
    public static final List<String> PARTS = List.of("members", "versions", "categories", "workPackages", "boards", "queries");

    private final DSLContext db;
    private final Journals journals;

    public ProjectCopy(DSLContext db, Journals journals) {
        this.db = db;
        this.journals = journals;
    }

    /** Copies into a project just created (its creator is already a member with the manager role). */
    public void copy(Ctx ctx, ProjectRow template, UUID target, Set<String> parts, boolean keepDescription) {
        var src = db.selectFrom(PROJECTS).where(PROJECTS.ID.eq(template.id())).fetchOne();
        var update = db.update(PROJECTS).set(PROJECTS.ENABLED_MODULES, src.getEnabledModules()).set(PROJECTS.CUSTOM_VALUES, src.getCustomValues())
                .set(PROJECTS.OVERVIEW_WIDGETS, src.getOverviewWidgets()).set(PROJECTS.INTERNAL_COMMENTS, src.getInternalComments());
        if (!keepDescription) update = update.set(PROJECTS.DESCRIPTION_HTML, src.getDescriptionHtml()).set(PROJECTS.DESCRIPTION_TEXT, src.getDescriptionText());
        update.where(PROJECTS.ID.eq(target)).execute();
        db.deleteFrom(PROJECT_TYPES).where(PROJECT_TYPES.PROJECT_ID.eq(target)).execute();
        db.insertInto(PROJECT_TYPES, PROJECT_TYPES.PROJECT_ID, PROJECT_TYPES.TYPE_ID)
                .select(db.select(org.jooq.impl.DSL.val(target), PROJECT_TYPES.TYPE_ID).from(PROJECT_TYPES).where(PROJECT_TYPES.PROJECT_ID.eq(template.id())))
                .execute();
        db.insertInto(PROJECT_CUSTOM_FIELDS, PROJECT_CUSTOM_FIELDS.PROJECT_ID, PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID)
                .select(db.select(org.jooq.impl.DSL.val(target), PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID).from(PROJECT_CUSTOM_FIELDS)
                        .where(PROJECT_CUSTOM_FIELDS.PROJECT_ID.eq(template.id())))
                .onConflictDoNothing().execute();

        if (parts.contains("members")) copyMembers(template.id(), target);
        var people = members(target);
        var ids = new HashMap<UUID, UUID>(); // template version/category/work package id → copy
        if (parts.contains("versions")) copyVersions(template.id(), target, ids);
        if (parts.contains("categories")) copyCategories(template.id(), target, people, ids);
        if (parts.contains("workPackages")) copyWorkPackages(ctx, template.id(), target, people, ids);
        if (parts.contains("boards")) copyBoards(ctx, template.id(), target, people, ids, parts.contains("workPackages"));
        if (parts.contains("queries")) copyQueries(ctx, template.id(), target, ids);
    }

    /** Users who are members of the project, directly or through a group. */
    private Set<UUID> members(UUID project) {
        var out = new HashSet<>(db.select(MEMBERS.USER_ID).from(MEMBERS).where(MEMBERS.PROJECT_ID.eq(project), MEMBERS.USER_ID.isNotNull()).fetch(MEMBERS.USER_ID));
        out.addAll(db.select(USER_GROUP_MEMBERS.USER_ID).from(USER_GROUP_MEMBERS).join(MEMBERS).on(MEMBERS.GROUP_ID.eq(USER_GROUP_MEMBERS.GROUP_ID))
                .where(MEMBERS.PROJECT_ID.eq(project)).fetch(USER_GROUP_MEMBERS.USER_ID));
        return out;
    }

    private UUID keepIf(UUID user, Set<UUID> people) {
        return user != null && people.contains(user) ? user : null;
    }

    private void copyMembers(UUID template, UUID target) {
        for (var m : db.selectFrom(MEMBERS).where(MEMBERS.PROJECT_ID.eq(template)).fetch()) {
            var existing = db.select(MEMBERS.ID).from(MEMBERS).where(MEMBERS.PROJECT_ID.eq(target),
                    m.getUserId() != null ? MEMBERS.USER_ID.eq(m.getUserId()) : MEMBERS.GROUP_ID.eq(m.getGroupId())).fetchOne(MEMBERS.ID);
            var id = existing != null ? existing
                    : db.insertInto(MEMBERS).set(MEMBERS.PROJECT_ID, target).set(MEMBERS.USER_ID, m.getUserId()).set(MEMBERS.GROUP_ID, m.getGroupId())
                            .returning(MEMBERS.ID).fetchOne().getId();
            // the creator keeps the manager role and also gets the roles the template gave them
            for (var role : db.select(MEMBER_ROLES.ROLE_ID).from(MEMBER_ROLES).where(MEMBER_ROLES.MEMBER_ID.eq(m.getId())).fetch(MEMBER_ROLES.ROLE_ID))
                db.insertInto(MEMBER_ROLES).set(MEMBER_ROLES.MEMBER_ID, id).set(MEMBER_ROLES.ROLE_ID, role).onConflictDoNothing().execute();
        }
    }

    private void copyVersions(UUID template, UUID target, Map<UUID, UUID> ids) {
        for (var v : db.selectFrom(VERSIONS).where(VERSIONS.PROJECT_ID.eq(template)).fetch()) {
            var made = db.insertInto(VERSIONS).set(VERSIONS.PROJECT_ID, target).set(VERSIONS.NAME, v.getName()).set(VERSIONS.DESCRIPTION, v.getDescription())
                    .set(VERSIONS.STATUS, v.getStatus()).set(VERSIONS.SHARING, v.getSharing()).set(VERSIONS.START_DATE, v.getStartDate())
                    .set(VERSIONS.END_DATE, v.getEndDate()).returning(VERSIONS.ID).fetchOne();
            ids.put(v.getId(), made.getId());
        }
    }

    private void copyCategories(UUID template, UUID target, Set<UUID> people, Map<UUID, UUID> ids) {
        for (var c : db.selectFrom(CATEGORIES).where(CATEGORIES.PROJECT_ID.eq(template)).fetch()) {
            var made = db.insertInto(CATEGORIES).set(CATEGORIES.PROJECT_ID, target).set(CATEGORIES.NAME, c.getName())
                    .set(CATEGORIES.DEFAULT_ASSIGNEE_ID, keepIf(c.getDefaultAssigneeId(), people)).returning(CATEGORIES.ID).fetchOne();
            ids.put(c.getId(), made.getId());
        }
    }

    private void copyWorkPackages(Ctx ctx, UUID template, UUID target, Set<UUID> people, Map<UUID, UUID> ids) {
        var sources = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(template)).orderBy(WORK_PACKAGES.NUMBER).fetch();
        var made = new LinkedHashMap<UUID, com.openpms.db.tables.records.WorkPackagesRecord>();
        for (var w : sources) {
            var c = db.newRecord(WORK_PACKAGES);
            c.from(w);
            for (var f : List.of(WORK_PACKAGES.ID, WORK_PACKAGES.NUMBER, WORK_PACKAGES.LOCK_VERSION, WORK_PACKAGES.CREATED_AT, WORK_PACKAGES.UPDATED_AT)) c.reset(f);
            c.setProjectId(target);
            c.setParentId(null); // set once every copy exists
            c.setAuthorId(ctx.userId());
            c.setAssigneeId(keepIf(w.getAssigneeId(), people));
            c.setResponsibleId(keepIf(w.getResponsibleId(), people));
            c.setVersionId(w.getVersionId() == null ? null : ids.get(w.getVersionId()));
            c.setCategoryId(w.getCategoryId() == null ? null : ids.get(w.getCategoryId()));
            c.store();
            c.refresh();
            made.put(w.getId(), c);
            ids.put(w.getId(), c.getId());
        }
        for (var w : sources) {
            if (w.getParentId() == null || !ids.containsKey(w.getParentId())) continue;
            db.update(WORK_PACKAGES).set(WORK_PACKAGES.PARENT_ID, ids.get(w.getParentId())).where(WORK_PACKAGES.ID.eq(ids.get(w.getId()))).execute();
        }
        if (!sources.isEmpty()) {
            var templateIds = sources.stream().map(com.openpms.db.tables.records.WorkPackagesRecord::getId).toList();
            for (var r : db.selectFrom(RELATIONS).where(RELATIONS.FROM_ID.in(templateIds), RELATIONS.TO_ID.in(templateIds)).fetch()) {
                db.insertInto(RELATIONS).set(RELATIONS.FROM_ID, ids.get(r.getFromId())).set(RELATIONS.TO_ID, ids.get(r.getToId())).set(RELATIONS.TYPE, r.getType())
                        .set(RELATIONS.LAG, r.getLag()).set(RELATIONS.DESCRIPTION, r.getDescription()).set(RELATIONS.CREATED_BY, ctx.userId()).execute();
            }
        }
        // a "created" entry in each copy's activity; copying a template notifies nobody
        for (var c : made.values()) {
            c.refresh();
            journals.write(ctx, c, new LinkedHashMap<>(Map.of(Journals.CREATED, Journals.listOf(null, true))), "", null, false);
        }
    }

    private void copyBoards(Ctx ctx, UUID template, UUID target, Set<UUID> people, Map<UUID, UUID> ids, boolean withCards) {
        for (var b : db.selectFrom(BOARDS).where(BOARDS.PROJECT_ID.eq(template), BOARDS.KIND.ne("subproject")).fetch()) {
            var board = db.insertInto(BOARDS).set(BOARDS.PROJECT_ID, target).set(BOARDS.NAME, b.getName()).set(BOARDS.KIND, b.getKind())
                    .set(BOARDS.IS_PUBLIC, b.getIsPublic()).set(BOARDS.FILTERS, remap(b.getFilters(), ids)).set(BOARDS.CREATED_BY, ctx.userId())
                    .returning(BOARDS.ID).fetchOne().getId();
            var columns = new HashMap<UUID, UUID>();
            int position = 0;
            for (var col : db.selectFrom(BOARD_COLUMNS).where(BOARD_COLUMNS.BOARD_ID.eq(b.getId())).orderBy(BOARD_COLUMNS.POSITION).fetch()) {
                UUID version = col.getVersionId() == null ? null : ids.get(col.getVersionId());
                UUID parent = col.getParentWorkPackageId() == null ? null : ids.get(col.getParentWorkPackageId());
                UUID user = keepIf(col.getUserId(), people);
                boolean keeps = col.getName() != null || col.getStatusId() != null || user != null || version != null || parent != null;
                if (!keeps) continue; // a value the new project does not have (another project's version, a non-member, a subproject)
                var made = db.insertInto(BOARD_COLUMNS).set(BOARD_COLUMNS.BOARD_ID, board).set(BOARD_COLUMNS.POSITION, position++)
                        .set(BOARD_COLUMNS.NAME, col.getName()).set(BOARD_COLUMNS.STATUS_ID, col.getStatusId()).set(BOARD_COLUMNS.USER_ID, user)
                        .set(BOARD_COLUMNS.VERSION_ID, version).set(BOARD_COLUMNS.PARENT_WORK_PACKAGE_ID, parent)
                        .returning(BOARD_COLUMNS.ID).fetchOne().getId();
                columns.put(col.getId(), made);
            }
            if (!withCards) continue;
            for (var card : db.selectFrom(BOARD_CARDS).where(BOARD_CARDS.BOARD_ID.eq(b.getId())).fetch()) {
                var wp = ids.get(card.getWorkPackageId());
                var column = card.getColumnId() == null ? null : columns.get(card.getColumnId());
                if (wp == null || (card.getColumnId() != null && column == null)) continue;
                db.insertInto(BOARD_CARDS).set(BOARD_CARDS.BOARD_ID, board).set(BOARD_CARDS.WORK_PACKAGE_ID, wp).set(BOARD_CARDS.COLUMN_ID, column)
                        .set(BOARD_CARDS.POSITION, card.getPosition()).execute();
            }
        }
    }

    private void copyQueries(Ctx ctx, UUID template, UUID target, Map<UUID, UUID> ids) {
        for (var q : db.selectFrom(QUERIES).where(QUERIES.PROJECT_ID.eq(template), QUERIES.IS_PUBLIC.isTrue().or(QUERIES.OWNER_ID.eq(ctx.userId()))).fetch()) {
            db.insertInto(QUERIES).set(QUERIES.PROJECT_ID, target).set(QUERIES.OWNER_ID, ctx.userId()).set(QUERIES.NAME, q.getName()).set(QUERIES.VIEW, q.getView())
                    .set(QUERIES.IS_PUBLIC, q.getIsPublic()).set(QUERIES.SPEC, remap(q.getSpec(), ids)).execute();
        }
    }

    /** Filter values that name a copied version or category point at the copy. */
    private static JSONB remap(JSONB json, Map<UUID, UUID> ids) {
        if (json == null) return null;
        var text = json.data();
        for (var e : ids.entrySet()) text = text.replace(e.getKey().toString(), e.getValue().toString());
        return JSONB.valueOf(text);
    }
}

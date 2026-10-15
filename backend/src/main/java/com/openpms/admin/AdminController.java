package com.openpms.admin;

import static com.openpms.db.Tables.BOARD_COLUMNS;
import static com.openpms.db.Tables.GLOBAL_ROLE_ASSIGNMENTS;
import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.MEMBER_ROLES;
import static com.openpms.db.Tables.NON_WORKING_DAYS;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.PRIORITIES;
import static com.openpms.db.Tables.PROJECT_TYPES;
import static com.openpms.db.Tables.ROLES;
import static com.openpms.db.Tables.STATUSES;
import static com.openpms.db.Tables.TYPES;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_GROUPS;
import static com.openpms.db.Tables.USER_GROUP_MEMBERS;
import static com.openpms.db.Tables.USER_PREFERENCES;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGE_SHARES;
import static com.openpms.db.Tables.WORKFLOWS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.jooq.impl.DSL.count;

import com.openpms.api.Dto;
import com.openpms.common.Html;
import tools.jackson.databind.json.JsonMapper;
import org.jooq.JSONB;
import com.openpms.wp.TypeForms;
import com.openpms.auth.Sessions;
import com.openpms.auth.Tokens;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.common.Mailer;
import com.openpms.common.SettingsRepo;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.core.Text;
import com.openpms.wp.ScheduleStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administration (S27–S36, F11). Everything is admin-only, except people and groups (global.manage_users). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** form: null = the default layout (S28) */
    public record AdminType(UUID id, String name, String color, boolean isMilestone, int position, boolean isDefault, boolean isInRoadmap,
                            long workPackageCount, long projectCount, Dto.TypeForm form, String descriptionTemplateHtml) {}

    public record AdminStatus(UUID id, String name, String color, boolean isClosed, boolean isReadonly, int position, boolean isDefault,
                              int defaultDoneRatio, boolean excludedFromTotals, boolean slaPause, long workPackageCount) {}

    public record AdminPriority(UUID id, String name, String color, int position, boolean isDefault, boolean isActive, long workPackageCount) {}

    public record AdminRole(UUID id, String name, String scope, String builtin, List<String> permissions, long memberCount) {}

    public record Permission(String key, String scope, String group) {}

    /** lockedUntil: a temporary lock after too many failed sign-ins, while it lasts (status stays active).
     *  temporaryPasswordUntil: until when the temporary password runs (past ones too until token-cleanup; never the password). */
    public record AdminUser(UUID id, String name, String email, String status, boolean isAdmin, String locale, String timeZone,
                            OffsetDateTime lastLoginAt, long projectCount, OffsetDateTime createdAt, OffsetDateTime lockedUntil, List<Dto.IdName> globalRoles,
                            OffsetDateTime temporaryPasswordUntil, Dto.UserRef manager) {}

    /** 임시 비밀번호로 만들기: the password is in this answer (and later in GET …/temporary-password), nowhere else. */
    public record CreatedWithPassword(AdminUser user, String temporaryPassword, OffsetDateTime expiresAt) {
        @Override
        public String toString() { // never the password itself (Spring MVC logs what it writes at DEBUG)
            return "CreatedWithPassword[user=" + (user == null ? null : user.id()) + ", expiresAt=" + expiresAt + "]";
        }
    }

    public record AdminGroup(UUID id, String name, List<Dto.UserRef> members, Dto.UserRef lead) {}

    public record CalendarSettings(List<Integer> workingDays, int hoursPerDay) {}

    public record Transitions(List<List<UUID>> transitions) {}

    /** Every permission key the app knows, grouped for the role editor (architecture.md 권한 키). */
    static final List<Permission> PERMISSIONS = new ArrayList<>();

    static {
        for (var k : List.of("project.view", "project.edit", "project.archive", "project.manage_modules", "project.manage_members", "project.view_members",
                "project.manage_versions", "project.manage_categories", "project.manage_types", "project.manage_custom_fields"))
            PERMISSIONS.add(new Permission(k, "project", "project"));
        for (var k : List.of("wp.view", "wp.create", "wp.edit", "wp.delete", "wp.move", "wp.attach", "wp.comment", "wp.edit_own_comments", "wp.edit_comments",
                "wp.view_internal_comments", "wp.add_internal_comments", "wp.manage_relations", "wp.manage_subtasks", "wp.assign_versions", "wp.view_watchers",
                "wp.add_watchers", "wp.delete_watchers", "wp.share", "wp.export"))
            PERMISSIONS.add(new Permission(k, "project", "wp"));
        for (var k : List.of("query.save", "query.manage_public")) PERMISSIONS.add(new Permission(k, "project", "query"));
        for (var k : List.of("board.view", "board.manage")) PERMISSIONS.add(new Permission(k, "project", "board"));
        for (var k : List.of("time.log", "time.view", "time.manage")) PERMISSIONS.add(new Permission(k, "project", "time"));
        for (var k : List.of("service.view", "service.handle", "service.manage")) PERMISSIONS.add(new Permission(k, "project", "service"));
        for (var k : List.of("global.create_projects", "global.manage_users")) PERMISSIONS.add(new Permission(k, "global", "global"));
    }

    private final Current current;
    private final DSLContext db;
    private final ScheduleStore schedules;
    private final SettingsRepo settings;
    private final Tokens tokens;
    private final Mailer mailer;
    private final Sessions sessions;

    private final JsonMapper json;
    private final com.openpms.auth.PasswordPolicy passwordPolicy;

    private final com.openpms.wp.Assignees assignees;
    private final com.openpms.auth.TemporaryPasswords temporaryPasswords;

    public AdminController(Current current, DSLContext db, ScheduleStore schedules, SettingsRepo settings, Tokens tokens, Mailer mailer, Sessions sessions,
                           JsonMapper json, com.openpms.auth.PasswordPolicy passwordPolicy, com.openpms.wp.Assignees assignees,
                           com.openpms.auth.TemporaryPasswords temporaryPasswords) {
        this.temporaryPasswords = temporaryPasswords;
        this.assignees = assignees;
        this.passwordPolicy = passwordPolicy;
        this.json = json;
        this.current = current;
        this.db = db;
        this.schedules = schedules;
        this.settings = settings;
        this.tokens = tokens;
        this.mailer = mailer;
        this.sessions = sessions;
    }

    /* ---------- guards and checks ---------- */

    private Ctx admin() {
        var ctx = current.ctx();
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
        return ctx;
    }

    private Ctx userManager() {
        var ctx = current.ctx();
        if (!ctx.isAdmin() && !ctx.globalPermissions().contains("global.manage_users")) throw ApiException.forbidden("사용자를 관리할 권한이 없습니다.");
        return ctx;
    }

    /** A user manager handles ordinary accounts; an administrator's account only another administrator (as with temporary passwords). */
    private static void requireAdminFor(Ctx ctx, com.openpms.db.tables.records.UsersRecord target) {
        if (target.getIsAdmin() && !ctx.isAdmin()) throw ApiException.forbidden("관리자 계정은 관리자만 다룰 수 있습니다.");
    }

    private static String name(Map<String, Object> in, boolean required) {
        if (!In.has(in, "name") && !required) return null;
        var n = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (n.isEmpty()) throw ApiException.field("name", "이름을 입력하세요.");
        if (n.length() > 100) throw ApiException.field("name", "이름은 100자 이하로 입력하세요.");
        return n;
    }

    private static String color(Map<String, Object> in, boolean required) {
        if (!In.has(in, "color") && !required) return null;
        var c = In.str(in, "color");
        if (c == null || !HEX.matcher(c).matches()) throw ApiException.field("color", "색은 #RRGGBB 형식으로 입력하세요.");
        return c;
    }

    private void unique(Table<?> table, Field<String> nameField, Field<UUID> idField, String name, UUID self) {
        if (name == null) return;
        var cond = nameField.eq(name);
        if (self != null) cond = cond.and(idField.ne(self));
        if (db.fetchExists(table, cond)) throw ApiException.field("name", "같은 이름이 이미 있습니다.");
    }

    private static ApiException notFound(String what) {
        return ApiException.notFound(what + "을(를) 찾을 수 없습니다.");
    }

    /** New order for a whole list (positions 1…n). */
    private void reorder(Table<?> table, Field<UUID> idField, Field<Integer> positionField, Map<String, Object> body) {
        var ids = In.uuids(body, "ids", "ids");
        var existing = new HashSet<>(db.select(idField).from(table).fetch(idField));
        if (ids.size() != existing.size() || !existing.containsAll(ids)) throw ApiException.unprocessable("순서 목록이 맞지 않습니다.", null);
        for (int i = 0; i < ids.size(); i++) db.update(table).set(positionField, i + 1).where(idField.eq(ids.get(i))).execute();
    }

    private void renumber(Table<?> table, Field<UUID> idField, Field<Integer> positionField) {
        var ids = db.select(idField).from(table).orderBy(positionField).fetch(idField);
        for (int i = 0; i < ids.size(); i++) db.update(table).set(positionField, i + 1).where(idField.eq(ids.get(i))).execute();
    }

    private long countWps(Field<UUID> field, UUID id) {
        return db.fetchCount(WORK_PACKAGES, field.eq(id));
    }

    /* ---------- types ---------- */

    private List<AdminType> types(UUID only) {
        var wp = db.select(WORK_PACKAGES.TYPE_ID, count()).from(WORK_PACKAGES).groupBy(WORK_PACKAGES.TYPE_ID).fetchMap(WORK_PACKAGES.TYPE_ID, count());
        var pr = db.select(PROJECT_TYPES.TYPE_ID, count()).from(PROJECT_TYPES).groupBy(PROJECT_TYPES.TYPE_ID).fetchMap(PROJECT_TYPES.TYPE_ID, count());
        return db.selectFrom(TYPES).orderBy(TYPES.POSITION).fetch().stream().filter(t -> only == null || t.getId().equals(only))
                .map(t -> new AdminType(t.getId(), t.getName(), t.getColor(), t.getIsMilestone(), t.getPosition(), t.getIsDefault(), t.getIsInRoadmap(),
                        wp.getOrDefault(t.getId(), 0), pr.getOrDefault(t.getId(), 0), TypeForms.read(json, t.getAttributeGroups()), t.getDescriptionTemplateHtml()))
                .toList();
    }

    @GetMapping("/types")
    public List<AdminType> listTypes() {
        admin();
        return types(null);
    }

    @PostMapping("/types")
    @Transactional
    public ResponseEntity<AdminType> createType(@RequestBody Map<String, Object> in) {
        admin();
        var n = name(in, true);
        var c = color(in, true);
        unique(TYPES, TYPES.NAME, TYPES.ID, n, null);
        var t = db.insertInto(TYPES).set(TYPES.NAME, n).set(TYPES.COLOR, c).set(TYPES.POSITION, db.fetchCount(TYPES) + 1)
                .set(TYPES.IS_MILESTONE, Boolean.TRUE.equals(In.bool(in, "isMilestone"))).set(TYPES.IS_DEFAULT, Boolean.TRUE.equals(In.bool(in, "isDefault")))
                .set(TYPES.IS_IN_ROADMAP, !Boolean.FALSE.equals(In.bool(in, "isInRoadmap"))).returning(TYPES.ID).fetchOne();
        return ResponseEntity.status(HttpStatus.CREATED).body(types(t.getId()).get(0));
    }

    @PatchMapping("/types/{id}")
    @Transactional
    public AdminType updateType(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        var t = db.selectFrom(TYPES).where(TYPES.ID.eq(id)).fetchOne();
        if (t == null) throw notFound("유형");
        var n = name(in, false);
        unique(TYPES, TYPES.NAME, TYPES.ID, n, id);
        if (n != null) t.setName(n);
        var c = color(in, false);
        if (c != null) t.setColor(c);
        if (In.has(in, "isMilestone")) {
            boolean ms = Boolean.TRUE.equals(In.bool(in, "isMilestone"));
            if (ms != t.getIsMilestone() && countWps(WORK_PACKAGES.TYPE_ID, id) > 0)
                throw ApiException.unprocessable("쓰고 있는 유형은 마일스톤 여부를 바꿀 수 없습니다.", "작업의 날짜 규칙이 달라지기 때문입니다.");
            t.setIsMilestone(ms);
        }
        if (In.has(in, "isDefault")) t.setIsDefault(Boolean.TRUE.equals(In.bool(in, "isDefault")));
        if (In.has(in, "isInRoadmap")) t.setIsInRoadmap(Boolean.TRUE.equals(In.bool(in, "isInRoadmap")));
        // S28: the form (null = back to the default) and the description new items of this type start with
        if (In.has(in, "form")) t.setAttributeGroups(in.get("form") == null ? JSONB.valueOf("[]") : TypeForms.write(json, TypeForms.parse(current.ctx().s(), in.get("form"))));
        if (In.has(in, "descriptionTemplateHtml")) t.setDescriptionTemplateHtml(Html.sanitize(In.str(in, "descriptionTemplateHtml")));
        t.store();
        return types(id).get(0);
    }

    @DeleteMapping("/types/{id}")
    @Transactional
    public ResponseEntity<Void> removeType(@PathVariable UUID id) {
        admin();
        long n = countWps(WORK_PACKAGES.TYPE_ID, id);
        if (n > 0) throw new ApiException(409, "쓰고 있는 유형입니다.", "이 유형의 작업 " + n + "개를 다른 유형으로 바꾼 뒤 삭제하세요.");
        db.deleteFrom(TYPES).where(TYPES.ID.eq(id)).execute(); // workflows and project_types cascade
        renumber(TYPES, TYPES.ID, TYPES.POSITION);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/types/order")
    @Transactional
    public ResponseEntity<Void> reorderTypes(@RequestBody Map<String, Object> body) {
        admin();
        reorder(TYPES, TYPES.ID, TYPES.POSITION, body);
        return ResponseEntity.noContent().build();
    }

    /* ---------- statuses ---------- */

    private List<AdminStatus> statuses(UUID only) {
        var wp = db.select(WORK_PACKAGES.STATUS_ID, count()).from(WORK_PACKAGES).groupBy(WORK_PACKAGES.STATUS_ID).fetchMap(WORK_PACKAGES.STATUS_ID, count());
        return db.selectFrom(STATUSES).orderBy(STATUSES.POSITION).fetch().stream().filter(s -> only == null || s.getId().equals(only))
                .map(s -> new AdminStatus(s.getId(), s.getName(), s.getColor(), s.getIsClosed(), s.getIsReadonly(), s.getPosition(), s.getIsDefault(),
                        s.getDefaultDoneRatio(), s.getExcludedFromTotals(), s.getSlaPause(), wp.getOrDefault(s.getId(), 0)))
                .toList();
    }

    private static Integer ratio(Map<String, Object> in) {
        var r = In.integer(in, "defaultDoneRatio", "defaultDoneRatio");
        if (r != null && (r < 0 || r > 100)) throw ApiException.field("defaultDoneRatio", "0에서 100 사이로 입력하세요.");
        return r;
    }

    @GetMapping("/statuses")
    public List<AdminStatus> listStatuses() {
        admin();
        return statuses(null);
    }

    @PostMapping("/statuses")
    @Transactional
    public ResponseEntity<AdminStatus> createStatus(@RequestBody Map<String, Object> in) {
        admin();
        var n = name(in, true);
        var c = color(in, true);
        unique(STATUSES, STATUSES.NAME, STATUSES.ID, n, null);
        var r = ratio(in);
        boolean isDefault = Boolean.TRUE.equals(In.bool(in, "isDefault"));
        if (isDefault) db.update(STATUSES).set(STATUSES.IS_DEFAULT, false).execute();
        var s = db.insertInto(STATUSES).set(STATUSES.NAME, n).set(STATUSES.COLOR, c).set(STATUSES.POSITION, db.fetchCount(STATUSES) + 1)
                .set(STATUSES.IS_DEFAULT, isDefault).set(STATUSES.IS_CLOSED, Boolean.TRUE.equals(In.bool(in, "isClosed")))
                .set(STATUSES.IS_READONLY, Boolean.TRUE.equals(In.bool(in, "isReadonly"))).set(STATUSES.DEFAULT_DONE_RATIO, (short) (r == null ? 0 : r))
                .set(STATUSES.EXCLUDED_FROM_TOTALS, Boolean.TRUE.equals(In.bool(in, "excludedFromTotals")))
                .set(STATUSES.SLA_PAUSE, Boolean.TRUE.equals(In.bool(in, "slaPause"))).returning(STATUSES.ID).fetchOne();
        return ResponseEntity.status(HttpStatus.CREATED).body(statuses(s.getId()).get(0));
    }

    @PatchMapping("/statuses/{id}")
    @Transactional
    public AdminStatus updateStatus(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        var s = db.selectFrom(STATUSES).where(STATUSES.ID.eq(id)).fetchOne();
        if (s == null) throw notFound("상태");
        var n = name(in, false);
        unique(STATUSES, STATUSES.NAME, STATUSES.ID, n, id);
        if (n != null) s.setName(n);
        var c = color(in, false);
        if (c != null) s.setColor(c);
        var r = ratio(in);
        if (r != null) s.setDefaultDoneRatio(r.shortValue());
        if (In.has(in, "isDefault")) {
            boolean d = Boolean.TRUE.equals(In.bool(in, "isDefault"));
            if (!d && s.getIsDefault()) throw ApiException.unprocessable("기본 상태는 하나 있어야 합니다.", "다른 상태를 기본으로 지정하면 이 상태의 기본 표시가 풀립니다.");
            if (d && !s.getIsDefault()) db.update(STATUSES).set(STATUSES.IS_DEFAULT, false).execute();
            s.setIsDefault(d);
        }
        if (In.has(in, "isClosed")) s.setIsClosed(Boolean.TRUE.equals(In.bool(in, "isClosed")));
        if (In.has(in, "isReadonly")) s.setIsReadonly(Boolean.TRUE.equals(In.bool(in, "isReadonly")));
        if (In.has(in, "excludedFromTotals")) s.setExcludedFromTotals(Boolean.TRUE.equals(In.bool(in, "excludedFromTotals")));
        // the SLA clock reads it from the snapshot, which every request loads afresh
        if (In.has(in, "slaPause")) s.setSlaPause(Boolean.TRUE.equals(In.bool(in, "slaPause")));
        s.changed(STATUSES.IS_DEFAULT, true);
        s.store();
        return statuses(id).get(0);
    }

    @DeleteMapping("/statuses/{id}")
    @Transactional
    public ResponseEntity<Void> removeStatus(@PathVariable UUID id) {
        admin();
        var s = db.selectFrom(STATUSES).where(STATUSES.ID.eq(id)).fetchOne();
        if (s == null) throw notFound("상태");
        if (s.getIsDefault()) throw new ApiException(409, "기본 상태는 삭제할 수 없습니다.", "다른 상태를 기본으로 지정한 뒤 삭제하세요.");
        long n = countWps(WORK_PACKAGES.STATUS_ID, id);
        if (n > 0) throw new ApiException(409, "쓰고 있는 상태입니다.", "이 상태의 작업 " + n + "개를 옮긴 뒤 삭제하세요.");
        db.deleteFrom(STATUSES).where(STATUSES.ID.eq(id)).execute(); // workflows and status board columns cascade
        renumber(STATUSES, STATUSES.ID, STATUSES.POSITION);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/statuses/order")
    @Transactional
    public ResponseEntity<Void> reorderStatuses(@RequestBody Map<String, Object> body) {
        admin();
        reorder(STATUSES, STATUSES.ID, STATUSES.POSITION, body);
        return ResponseEntity.noContent().build();
    }

    /* ---------- priorities ---------- */

    private List<AdminPriority> priorities(UUID only) {
        var wp = db.select(WORK_PACKAGES.PRIORITY_ID, count()).from(WORK_PACKAGES).groupBy(WORK_PACKAGES.PRIORITY_ID).fetchMap(WORK_PACKAGES.PRIORITY_ID, count());
        return db.selectFrom(PRIORITIES).orderBy(PRIORITIES.POSITION).fetch().stream().filter(p -> only == null || p.getId().equals(only))
                .map(p -> new AdminPriority(p.getId(), p.getName(), p.getColor(), p.getPosition(), p.getIsDefault(), p.getIsActive(), wp.getOrDefault(p.getId(), 0)))
                .toList();
    }

    @GetMapping("/priorities")
    public List<AdminPriority> listPriorities() {
        admin();
        return priorities(null);
    }

    @PostMapping("/priorities")
    @Transactional
    public ResponseEntity<AdminPriority> createPriority(@RequestBody Map<String, Object> in) {
        admin();
        var n = name(in, true);
        var c = color(in, true);
        unique(PRIORITIES, PRIORITIES.NAME, PRIORITIES.ID, n, null);
        boolean isDefault = Boolean.TRUE.equals(In.bool(in, "isDefault"));
        if (isDefault) db.update(PRIORITIES).set(PRIORITIES.IS_DEFAULT, false).execute();
        var p = db.insertInto(PRIORITIES).set(PRIORITIES.NAME, n).set(PRIORITIES.COLOR, c).set(PRIORITIES.POSITION, db.fetchCount(PRIORITIES) + 1)
                .set(PRIORITIES.IS_DEFAULT, isDefault).set(PRIORITIES.IS_ACTIVE, !Boolean.FALSE.equals(In.bool(in, "isActive"))).returning(PRIORITIES.ID).fetchOne();
        return ResponseEntity.status(HttpStatus.CREATED).body(priorities(p.getId()).get(0));
    }

    @PatchMapping("/priorities/{id}")
    @Transactional
    public AdminPriority updatePriority(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        var p = db.selectFrom(PRIORITIES).where(PRIORITIES.ID.eq(id)).fetchOne();
        if (p == null) throw notFound("우선순위");
        var n = name(in, false);
        unique(PRIORITIES, PRIORITIES.NAME, PRIORITIES.ID, n, id);
        if (n != null) p.setName(n);
        var c = color(in, false);
        if (c != null) p.setColor(c);
        boolean willBeDefault = In.has(in, "isDefault") ? Boolean.TRUE.equals(In.bool(in, "isDefault")) : p.getIsDefault();
        if (In.has(in, "isDefault") && !willBeDefault && p.getIsDefault()) throw ApiException.unprocessable("기본 우선순위는 하나 있어야 합니다.", null);
        if (In.has(in, "isActive") && Boolean.FALSE.equals(In.bool(in, "isActive")) && willBeDefault) throw ApiException.unprocessable("기본 우선순위는 숨길 수 없습니다.", null);
        if (willBeDefault && !p.getIsDefault()) db.update(PRIORITIES).set(PRIORITIES.IS_DEFAULT, false).execute();
        p.setIsDefault(willBeDefault);
        if (In.has(in, "isActive")) p.setIsActive(!Boolean.FALSE.equals(In.bool(in, "isActive")));
        p.changed(PRIORITIES.IS_DEFAULT, true);
        p.store();
        return priorities(id).get(0);
    }

    @DeleteMapping("/priorities/{id}")
    @Transactional
    public ResponseEntity<Void> removePriority(@PathVariable UUID id) {
        admin();
        var p = db.selectFrom(PRIORITIES).where(PRIORITIES.ID.eq(id)).fetchOne();
        if (p == null) throw notFound("우선순위");
        if (p.getIsDefault()) throw new ApiException(409, "기본 우선순위는 삭제할 수 없습니다.");
        long n = countWps(WORK_PACKAGES.PRIORITY_ID, id);
        if (n > 0) throw new ApiException(409, "쓰고 있는 우선순위입니다.", "이 우선순위의 작업 " + n + "개를 바꾼 뒤 삭제하세요. 숨기기를 쓰면 새 작업에서만 빠집니다.");
        p.delete();
        renumber(PRIORITIES, PRIORITIES.ID, PRIORITIES.POSITION);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/priorities/order")
    @Transactional
    public ResponseEntity<Void> reorderPriorities(@RequestBody Map<String, Object> body) {
        admin();
        reorder(PRIORITIES, PRIORITIES.ID, PRIORITIES.POSITION, body);
        return ResponseEntity.noContent().build();
    }

    /* ---------- workflows ---------- */

    @GetMapping("/workflows")
    public Transitions workflows(@RequestParam UUID typeId, @RequestParam UUID roleId) {
        admin();
        return new Transitions(db.select(WORKFLOWS.FROM_STATUS_ID, WORKFLOWS.TO_STATUS_ID).from(WORKFLOWS)
                .where(WORKFLOWS.TYPE_ID.eq(typeId), WORKFLOWS.ROLE_ID.eq(roleId), WORKFLOWS.APPLIES_TO.eq("always"))
                .fetch(r -> List.of(r.value1(), r.value2())));
    }

    @PutMapping("/workflows")
    @Transactional
    public ResponseEntity<Void> saveWorkflow(@RequestBody Map<String, Object> body) {
        var ctx = admin();
        var s = ctx.s();
        var typeId = In.uuid(body, "typeId", "type");
        var roleId = In.uuid(body, "roleId", "role");
        if (s.type(typeId).isEmpty() || s.role(roleId).filter(r -> "project".equals(r.scope())).isEmpty()) throw notFound("유형 또는 역할");
        var statusIds = new HashSet<UUID>();
        s.statuses().forEach(x -> statusIds.add(x.id()));
        var pairs = new LinkedHashSet<List<UUID>>();
        if (body.get("transitions") instanceof List<?> l) for (var t : l) {
            if (!(t instanceof List<?> pair) || pair.size() != 2) continue;
            var a = In.uuidOrNull(String.valueOf(pair.get(0)));
            var b = In.uuidOrNull(String.valueOf(pair.get(1)));
            if (a != null && b != null && !a.equals(b) && statusIds.contains(a) && statusIds.contains(b)) pairs.add(List.of(a, b));
        }
        db.deleteFrom(WORKFLOWS).where(WORKFLOWS.TYPE_ID.eq(typeId), WORKFLOWS.ROLE_ID.eq(roleId), WORKFLOWS.APPLIES_TO.eq("always")).execute();
        for (var p : pairs) {
            db.insertInto(WORKFLOWS).set(WORKFLOWS.TYPE_ID, typeId).set(WORKFLOWS.ROLE_ID, roleId).set(WORKFLOWS.FROM_STATUS_ID, p.get(0))
                    .set(WORKFLOWS.TO_STATUS_ID, p.get(1)).execute();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workflows/copy")
    @Transactional
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> copyWorkflow(@RequestBody Map<String, Object> body) {
        admin();
        var from = (Map<String, Object>) body.get("from");
        var to = (Map<String, Object>) body.get("to");
        var fromType = In.uuid(from, "typeId", "from");
        var fromRole = In.uuid(from, "roleId", "from");
        var src = db.select(WORKFLOWS.FROM_STATUS_ID, WORKFLOWS.TO_STATUS_ID, WORKFLOWS.APPLIES_TO).from(WORKFLOWS)
                .where(WORKFLOWS.TYPE_ID.eq(fromType), WORKFLOWS.ROLE_ID.eq(fromRole)).fetch();
        for (var typeId : In.uuids(to, "typeIds", "to")) for (var roleId : In.uuids(to, "roleIds", "to")) {
            if (typeId.equals(fromType) && roleId.equals(fromRole)) continue;
            db.deleteFrom(WORKFLOWS).where(WORKFLOWS.TYPE_ID.eq(typeId), WORKFLOWS.ROLE_ID.eq(roleId)).execute();
            for (var r : src) db.insertInto(WORKFLOWS).set(WORKFLOWS.TYPE_ID, typeId).set(WORKFLOWS.ROLE_ID, roleId).set(WORKFLOWS.FROM_STATUS_ID, r.value1())
                    .set(WORKFLOWS.TO_STATUS_ID, r.value2()).set(WORKFLOWS.APPLIES_TO, r.value3()).execute();
        }
        return ResponseEntity.noContent().build();
    }

    /* ---------- roles ---------- */

    private List<AdminRole> roles(UUID only) {
        var members = db.select(MEMBER_ROLES.ROLE_ID, count()).from(MEMBER_ROLES).groupBy(MEMBER_ROLES.ROLE_ID).fetchMap(MEMBER_ROLES.ROLE_ID, count());
        return db.selectFrom(ROLES).orderBy(ROLES.POSITION, ROLES.NAME).fetch().stream().filter(r -> only == null || r.getId().equals(only))
                .map(r -> new AdminRole(r.getId(), r.getName(), r.getScope(), r.getBuiltin(), List.of(r.getPermissions()),
                        "project".equals(r.getScope()) ? members.getOrDefault(r.getId(), 0) : 0))
                .toList();
    }

    /**
     * Project roles are also read by people who manage a project's members (the add-members dialog lists
     * them, architecture.md 계약 사항 4); everything else here is admin-only.
     */
    @GetMapping("/roles")
    public List<AdminRole> listRoles(@RequestParam(required = false) String scope) {
        var ctx = current.ctx();
        if ("project".equals(scope)) {
            boolean managesMembers = ctx.isAdmin() || ctx.s().projects().values().stream().anyMatch(p -> ctx.can(p, "project.manage_members"));
            if (!managesMembers) throw ApiException.forbidden("역할 목록을 볼 권한이 없습니다.");
            return roles(null).stream().filter(r -> "project".equals(r.scope()) && r.builtin() == null).toList();
        }
        admin();
        return roles(null);
    }

    private static String[] known(String scope, List<String> permissions) {
        var keys = PERMISSIONS.stream().filter(p -> p.scope().equals(scope)).map(Permission::key).toList();
        return (permissions == null ? List.<String>of() : permissions).stream().filter(keys::contains).distinct().toArray(String[]::new);
    }

    @PostMapping("/roles")
    @Transactional
    public ResponseEntity<AdminRole> createRole(@RequestBody Map<String, Object> in) {
        admin();
        var n = name(in, true);
        unique(ROLES, ROLES.NAME, ROLES.ID, n, null);
        var scope = In.str(in, "scope");
        if (!"project".equals(scope) && !"global".equals(scope)) throw ApiException.field("scope", "역할 범위를 고르세요.");
        var r = db.insertInto(ROLES).set(ROLES.NAME, n).set(ROLES.SCOPE, scope).set(ROLES.PERMISSIONS, known(scope, In.strings(in, "permissions")))
                .set(ROLES.POSITION, db.fetchCount(ROLES) + 1).returning(ROLES.ID).fetchOne();
        return ResponseEntity.status(HttpStatus.CREATED).body(roles(r.getId()).get(0));
    }

    @PatchMapping("/roles/{id}")
    @Transactional
    public AdminRole updateRole(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        var r = db.selectFrom(ROLES).where(ROLES.ID.eq(id)).fetchOne();
        if (r == null) throw notFound("역할");
        var n = name(in, false);
        unique(ROLES, ROLES.NAME, ROLES.ID, n, id);
        if (n != null) r.setName(n);
        if (In.has(in, "permissions")) r.setPermissions(known(r.getScope(), In.strings(in, "permissions")));
        r.store();
        return roles(id).get(0);
    }

    @DeleteMapping("/roles/{id}")
    @Transactional
    public ResponseEntity<Void> removeRole(@PathVariable UUID id) {
        admin();
        var r = db.selectFrom(ROLES).where(ROLES.ID.eq(id)).fetchOne();
        if (r == null) throw notFound("역할");
        if (r.getBuiltin() != null) throw new ApiException(409, "내장 역할은 삭제할 수 없습니다.", "권한만 바꿀 수 있습니다.");
        long n = db.fetchCount(MEMBER_ROLES, MEMBER_ROLES.ROLE_ID.eq(id));
        if (n > 0) throw new ApiException(409, "쓰고 있는 역할입니다.", "이 역할을 가진 멤버십 " + n + "개를 다른 역할로 바꾼 뒤 삭제하세요.");
        r.delete(); // workflows and global assignments cascade
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/permissions")
    public List<Permission> permissions() {
        admin();
        return PERMISSIONS;
    }

    /* ---------- users ---------- */

    /** Sign-in failures are not in the snapshot, so the running lockouts are read fresh. */
    private Map<UUID, OffsetDateTime> lockouts() {
        return db.select(USERS.ID, USERS.LOCKED_UNTIL).from(USERS).where(USERS.LOCKED_UNTIL.gt(OffsetDateTime.now())).fetchMap(USERS.ID, USERS.LOCKED_UNTIL);
    }

    private AdminUser toUser(Ctx ctx, Snapshot.UserRow u, Map<UUID, OffsetDateTime> lockouts, Map<UUID, OffsetDateTime> temporary) {
        var s = ctx.s();
        long projects = s.projects().values().stream().filter(p -> s.isMember(u.id(), p.id())).count();
        var globalRoles = s.globalRoleIds(u.id()).stream().map(id -> s.roles().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).sorted(Comparator.comparing(Snapshot.RoleRow::name, Text.KOREAN)).map(r -> new Dto.IdName(r.id(), r.name())).toList();
        return new AdminUser(u.id(), u.displayName(), u.email(), u.status(), u.isAdmin(), u.locale(), u.timeZone(), u.lastLoginAt(), projects, u.createdAt(),
                "active".equals(u.status()) ? lockouts.get(u.id()) : null, globalRoles, "deleted".equals(u.status()) ? null : temporary.get(u.id()),
                "deleted".equals(u.status()) ? null : Refs.user(s, u.managerId()));
    }

    private AdminUser toUser(Ctx ctx, Snapshot.UserRow u) {
        return toUser(ctx, u, lockouts(), temporaryPasswords.untilByUser());
    }

    /** The 잠김 filter also finds people locked out for a while by failed sign-ins. */
    @GetMapping("/users")
    public List<AdminUser> listUsers(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Boolean noManager) {
        var ctx = userManager();
        var needle = q == null ? "" : q.trim().toLowerCase();
        var lockouts = lockouts();
        var temporary = temporaryPasswords.untilByUser();
        return ctx.s().users().values().stream()
                .filter(u -> !"deleted".equals(u.status()) || "deleted".equals(status))
                .filter(u -> status == null || status.isBlank() || status.equals(u.status())
                        || "locked".equals(status) && "active".equals(u.status()) && lockouts.containsKey(u.id()))
                .filter(u -> !Boolean.TRUE.equals(noManager) || "active".equals(u.status()) && u.managerId() == null)
                .filter(u -> needle.isEmpty() || u.name().toLowerCase().contains(needle) || u.email().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Snapshot.UserRow::name, Text.KOREAN)).map(u -> toUser(ctx, u, lockouts, temporary)).toList();
    }

    private void sendInvitation(Ctx ctx, UUID userId, String email, String name) {
        tokens.revokeAll(userId, "invitation"); // a resent invitation replaces the earlier link
        var token = tokens.issue(userId, "invitation", Duration.ofHours(settings.integer("auth.invitation_ttl_hours", 168)), ctx.userId());
        mailer.send(email, "[openPMS] 초대장이 도착했습니다",
                name + "님,\n\n" + ctx.user().name() + "님이 openPMS에 초대했습니다. 아래 링크에서 비밀번호를 정하면 바로 쓸 수 있습니다.\n\n"
                        + mailer.link("/invitations/" + token) + "\n\n링크는 한 번만 쓸 수 있고 일정 기간이 지나면 만료됩니다.");
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> in, HttpServletRequest req) {
        var ctx = userManager();
        var method = In.str(in, "method") == null ? "invite" : In.str(in, "method");
        if (!"invite".equals(method) && !"temporaryPassword".equals(method)) throw ApiException.field("method", "알 수 없는 등록 방법입니다.");
        boolean temporary = "temporaryPassword".equals(method);
        // an API token must not become a way to get a person's password (ApiTokenFilter closes the other temporary-password routes)
        if (temporary && com.openpms.auth.ApiTokenFilter.isBearer(req))
            throw ApiException.forbidden("API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.");
        var n = name(in, true);
        var email = In.str(in, "email") == null ? "" : In.str(in, "email").trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) throw ApiException.field("email", "이메일 형식이 아닙니다.");
        if (db.fetchExists(USERS, USERS.EMAIL.eq(email))) throw ApiException.field("email", "이미 등록된 이메일입니다.");
        boolean isAdmin = Boolean.TRUE.equals(In.bool(in, "isAdmin"));
        if (isAdmin && !ctx.isAdmin()) throw ApiException.forbidden("관리자만 관리자를 지정할 수 있습니다.");
        var managerId = In.has(in, "managerId") ? activeUser(in, "managerId", null) : null;
        var u = db.insertInto(USERS).set(USERS.EMAIL, email).set(USERS.NAME, n).set(USERS.STATUS, temporary ? "active" : "invited").set(USERS.IS_ADMIN, isAdmin)
                .set(USERS.MANAGER_ID, managerId).returning(USERS.ID).fetchOne();
        db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, u.getId()).execute();
        if (!temporary) {
            sendInvitation(ctx, u.getId(), email, n);
            ctx.refresh();
            return ResponseEntity.status(HttpStatus.CREATED).body(toUser(ctx, ctx.s().user(u.getId()).orElseThrow()));
        }
        var issued = temporaryPasswords.issue(u.getId(), ctx.userId());
        ctx.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new CreatedWithPassword(toUser(ctx, ctx.s().user(u.getId()).orElseThrow()), issued.temporaryPassword(), issued.expiresAt()));
    }

    /** A person named by a body field (승인자의 관리자, 그룹 책임자): active; null clears. {@code self} may not name itself. */
    private UUID activeUser(Map<String, Object> in, String field, UUID self) {
        var id = In.uuid(in, field, field);
        if (id == null) return null;
        if (id.equals(self)) throw ApiException.field(field, "자기 자신을 관리자로 지정할 수 없습니다.");
        if (!db.fetchExists(USERS, USERS.ID.eq(id), USERS.STATUS.eq("active"))) throw ApiException.field(field, "활성 사용자만 지정할 수 있습니다.");
        return id;
    }

    private long activeAdmins() {
        return db.fetchCount(USERS, USERS.IS_ADMIN.isTrue().and(USERS.STATUS.eq("active")));
    }

    @PatchMapping("/users/{id}")
    @Transactional
    public AdminUser updateUser(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = userManager();
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id), USERS.STATUS.ne("deleted")).fetchOne();
        if (u == null) throw notFound("사용자");
        requireAdminFor(ctx, u);
        var n = name(in, false);
        if (n != null) u.setName(n);
        if (In.has(in, "isAdmin")) {
            if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
            boolean isAdmin = Boolean.TRUE.equals(In.bool(in, "isAdmin"));
            if (!isAdmin && u.getId().equals(ctx.userId())) throw ApiException.unprocessable("내 관리자 권한은 내가 해제할 수 없습니다.", null);
            if (!isAdmin && u.getIsAdmin() && activeAdmins() <= 1) throw ApiException.unprocessable("마지막 관리자입니다.", null);
            u.setIsAdmin(isAdmin);
        }
        if (In.has(in, "status")) {
            var st = In.str(in, "status");
            if (!"active".equals(st) && !"locked".equals(st)) throw ApiException.field("status", "알 수 없는 상태입니다.");
            if (u.getId().equals(ctx.userId())) throw ApiException.unprocessable("내 계정은 잠글 수 없습니다.", null);
            if ("invited".equals(u.getStatus()) && "active".equals(st)) throw ApiException.unprocessable("초대를 수락하기 전에는 활성화할 수 없습니다.", null);
            if ("locked".equals(st) && u.getIsAdmin() && activeAdmins() <= 1) throw ApiException.unprocessable("마지막 관리자입니다.", null);
            if ("active".equals(st)) {
                u.setFailedLoginCount(0);
                u.setLockedUntil(null);
            }
            u.setStatus(st);
            if ("locked".equals(st)) sessions.endAll(u.getId());
        }
        if (In.has(in, "managerId")) u.setManagerId(activeUser(in, "managerId", id));
        u.store();
        ctx.refresh();
        return toUser(ctx, ctx.s().user(id).orElseThrow());
    }

    /**
     * 전역 역할 (S31·S35): the global roles a person holds, replaced as a whole. Only administrators may hand them out,
     * since a global role grants rights across the instance (create projects, manage users).
     */
    @PutMapping("/users/{id}/global-roles")
    @Transactional
    public AdminUser setGlobalRoles(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = userManager();
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 전역 역할을 줄 수 있습니다.");
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id), USERS.STATUS.ne("deleted")).fetchOne();
        if (u == null) throw notFound("사용자");
        var roleIds = In.uuids(in, "roleIds", "roleIds");
        var global = ctx.s().roles().stream().filter(r -> "global".equals(r.scope())).map(Snapshot.RoleRow::id).toList();
        if (!global.containsAll(roleIds)) throw ApiException.field("roleIds", "전역 역할만 줄 수 있습니다.");
        db.deleteFrom(GLOBAL_ROLE_ASSIGNMENTS).where(GLOBAL_ROLE_ASSIGNMENTS.USER_ID.eq(id)).execute();
        for (var r : new java.util.LinkedHashSet<>(roleIds))
            db.insertInto(GLOBAL_ROLE_ASSIGNMENTS).set(GLOBAL_ROLE_ASSIGNMENTS.USER_ID, id).set(GLOBAL_ROLE_ASSIGNMENTS.ROLE_ID, r).execute();
        ctx.refresh();
        return toUser(ctx, ctx.s().user(id).orElseThrow());
    }

    /** Anonymise (architecture.md §2): journals stay, personal data and access go. */
    @DeleteMapping("/users/{id}")
    @Transactional
    public ResponseEntity<Void> removeUser(@PathVariable UUID id) {
        var ctx = userManager();
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id), USERS.STATUS.ne("deleted")).fetchOne();
        if (u == null) throw notFound("사용자");
        requireAdminFor(ctx, u);
        if (u.getId().equals(ctx.userId())) throw ApiException.unprocessable("내 계정은 삭제할 수 없습니다.", null);
        if (u.getIsAdmin() && activeAdmins() <= 1) throw ApiException.unprocessable("마지막 관리자입니다.", null);
        db.update(USERS).set(USERS.STATUS, "deleted").set(USERS.NAME, "삭제된 사용자").set(USERS.EMAIL, "deleted+" + id + "@invalid")
                .setNull(USERS.PASSWORD_HASH).set(USERS.IS_ADMIN, false).where(USERS.ID.eq(id)).execute();
        db.deleteFrom(MEMBERS).where(MEMBERS.USER_ID.eq(id)).execute();
        db.deleteFrom(WATCHERS).where(WATCHERS.USER_ID.eq(id)).execute();
        db.deleteFrom(WORK_PACKAGE_SHARES).where(WORK_PACKAGE_SHARES.USER_ID.eq(id)).execute();
        db.deleteFrom(NOTIFICATIONS).where(NOTIFICATIONS.RECIPIENT_ID.eq(id)).execute();
        db.deleteFrom(USER_GROUP_MEMBERS).where(USER_GROUP_MEMBERS.USER_ID.eq(id)).execute();
        db.deleteFrom(GLOBAL_ROLE_ASSIGNMENTS).where(GLOBAL_ROLE_ASSIGNMENTS.USER_ID.eq(id)).execute();
        db.deleteFrom(BOARD_COLUMNS).where(BOARD_COLUMNS.USER_ID.eq(id)).execute();
        // 공동 담당자: off every list; where they were the main assignee the next person takes over
        var theirs = db.select(com.openpms.db.Tables.WORK_PACKAGE_ASSIGNEES.WORK_PACKAGE_ID).from(com.openpms.db.Tables.WORK_PACKAGE_ASSIGNEES)
                .where(com.openpms.db.Tables.WORK_PACKAGE_ASSIGNEES.USER_ID.eq(id)).fetch(r -> r.value1());
        for (var wpId : theirs) assignees.write(wpId, assignees.of(wpId).stream().filter(e -> !e.userId().equals(id)).toList());
        db.update(WORK_PACKAGES).setNull(WORK_PACKAGES.ASSIGNEE_ID).where(WORK_PACKAGES.ASSIGNEE_ID.eq(id)).execute();
        db.update(WORK_PACKAGES).setNull(WORK_PACKAGES.RESPONSIBLE_ID).where(WORK_PACKAGES.RESPONSIBLE_ID.eq(id)).execute();
        temporaryPasswords.clear(id); // no plain password stays behind an anonymised account
        sessions.endAll(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/resend-invitation")
    @Transactional
    public ResponseEntity<Void> resendInvitation(@PathVariable UUID id) {
        var ctx = userManager();
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne();
        if (u == null) throw notFound("사용자");
        if (!"invited".equals(u.getStatus())) throw ApiException.unprocessable("초대 대기 중인 사용자만 다시 보낼 수 있습니다.", null);
        sendInvitation(ctx, u.getId(), u.getEmail(), u.getName());
        return ResponseEntity.noContent().build();
    }

    /* ---------- groups ---------- */

    private AdminGroup toGroup(Ctx ctx, UUID id) {
        var s = ctx.s();
        var g = s.groups().get(id);
        return new AdminGroup(g.id(), g.name(), g.memberIds().stream().map(u -> Refs.user(s, u)).sorted(Comparator.comparing(Dto.UserRef::name, Text.KOREAN)).toList(), Refs.user(s, g.leadUserId()));
    }

    @GetMapping("/groups")
    public List<AdminGroup> listGroups() {
        var ctx = userManager();
        return ctx.s().groups().values().stream().sorted(Comparator.comparing(Snapshot.GroupRow::name, Text.KOREAN)).map(g -> toGroup(ctx, g.id())).toList();
    }

    @PostMapping("/groups")
    @Transactional
    public ResponseEntity<AdminGroup> createGroup(@RequestBody Map<String, Object> in) {
        var ctx = userManager();
        var n = name(in, true);
        unique(USER_GROUPS, USER_GROUPS.NAME, USER_GROUPS.ID, n, null);
        var lead = In.has(in, "leadUserId") ? activeUser(in, "leadUserId", null) : null;
        var g = db.insertInto(USER_GROUPS).set(USER_GROUPS.NAME, n).set(USER_GROUPS.LEAD_USER_ID, lead).returning(USER_GROUPS.ID).fetchOne();
        ctx.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(toGroup(ctx, g.getId()));
    }

    @PatchMapping("/groups/{id}")
    @Transactional
    public AdminGroup renameGroup(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = userManager();
        if (!ctx.s().groups().containsKey(id)) throw notFound("그룹");
        var n = name(in, !In.has(in, "leadUserId"));
        if (n != null) {
            unique(USER_GROUPS, USER_GROUPS.NAME, USER_GROUPS.ID, n, id);
            db.update(USER_GROUPS).set(USER_GROUPS.NAME, n).where(USER_GROUPS.ID.eq(id)).execute();
        }
        if (In.has(in, "leadUserId")) db.update(USER_GROUPS).set(USER_GROUPS.LEAD_USER_ID, activeUser(in, "leadUserId", null)).where(USER_GROUPS.ID.eq(id)).execute();
        ctx.refresh();
        return toGroup(ctx, id);
    }

    @DeleteMapping("/groups/{id}")
    @Transactional
    public ResponseEntity<Void> removeGroup(@PathVariable UUID id) {
        userManager();
        db.deleteFrom(USER_GROUPS).where(USER_GROUPS.ID.eq(id)).execute(); // group members and project memberships cascade
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/groups/{id}/members")
    @Transactional
    public AdminGroup setGroupMembers(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        var ctx = userManager();
        if (!ctx.s().groups().containsKey(id)) throw notFound("그룹");
        var s = ctx.s();
        var ids = In.uuids(body, "userIds", "users").stream().filter(u -> s.user(u).filter(x -> !"deleted".equals(x.status())).isPresent()).distinct().toList();
        db.deleteFrom(USER_GROUP_MEMBERS).where(USER_GROUP_MEMBERS.GROUP_ID.eq(id)).execute();
        for (var u : ids) db.insertInto(USER_GROUP_MEMBERS).set(USER_GROUP_MEMBERS.GROUP_ID, id).set(USER_GROUP_MEMBERS.USER_ID, u).execute();
        ctx.refresh();
        return toGroup(ctx, id);
    }

    /* ---------- work calendar (S33) ---------- */

    /** After the calendar changed: keep starts and durations, move finish dates, journal each move (S11). */
    private void rescheduleAll(Ctx ctx) {
        ctx.refresh();
        schedules.lock();
        var graph = schedules.load(ctx);
        schedules.apply(ctx, graph.rescheduleAll());
    }

    /* ---------- 로그인과 비밀번호 ---------- */

    @GetMapping("/settings/auth")
    public com.openpms.auth.PasswordPolicy.Settings authSettings() {
        admin();
        return passwordPolicy.current();
    }

    @PutMapping("/settings/auth")
    @Transactional
    public com.openpms.auth.PasswordPolicy.Settings saveAuthSettings(@RequestBody Map<String, Object> in) {
        var ctx = admin();
        java.util.function.BiFunction<String, int[], Integer> num = (key, range) -> {
            var v = In.integer(in, key, key);
            if (v == null || v < range[0] || v > range[1]) throw ApiException.field(key, range[0] + "에서 " + range[1] + " 사이로 입력하세요.");
            return v;
        };
        int minLength = num.apply("minLength", new int[] {8, 128});
        int minClasses = num.apply("minClasses", new int[] {0, 4});
        int lockoutAttempts = num.apply("lockoutAttempts", new int[] {0, 50});
        int lockoutMinutes = num.apply("lockoutMinutes", new int[] {1, 1440});
        int invitationTtlHours = num.apply("invitationTtlHours", new int[] {1, 720});
        int resetTtlMinutes = num.apply("resetTtlMinutes", new int[] {10, 1440});
        var classes = In.strings(in, "requiredClasses");
        if (classes == null) classes = List.of();
        for (var c : classes) if (!com.openpms.auth.PasswordPolicy.CLASSES.contains(c)) throw ApiException.field("requiredClasses", "알 수 없는 문자 종류입니다: " + c);
        if (minClasses < classes.stream().distinct().count())
            throw ApiException.field("minClasses", "꼭 넣어야 하는 종류(" + classes.stream().distinct().count() + "가지)보다 적게 정할 수 없습니다.");
        var by = ctx.userId();
        settings.put("auth.password_min_length", String.valueOf(minLength), by);
        settings.put("auth.password_required_classes", json.writeValueAsString(classes.stream().distinct().toList()), by);
        settings.put("auth.password_min_classes", String.valueOf(minClasses), by);
        settings.put("auth.password_reset_enabled", String.valueOf(!Boolean.FALSE.equals(In.bool(in, "resetEnabled"))), by);
        settings.put("auth.lockout_attempts", String.valueOf(lockoutAttempts), by);
        settings.put("auth.lockout_minutes", String.valueOf(lockoutMinutes), by);
        settings.put("auth.invitation_ttl_hours", String.valueOf(invitationTtlHours), by);
        settings.put("auth.password_reset_ttl_minutes", String.valueOf(resetTtlMinutes), by);
        return passwordPolicy.current();
    }

    /* ---------- progress tracking (S32) ---------- */

    @GetMapping("/settings/progress")
    public Dto.ProgressSettings progressSettings() {
        var p = admin().s().settings().progress();
        return new Dto.ProgressSettings(p.mode(), p.closedSets100(), p.totals(), null);
    }

    /** Saves the rules and recalculates every work package (no journals: a setting changed, not the work; times stay). */
    @PutMapping("/settings/progress")
    @Transactional
    public Dto.ProgressSettings saveProgressSettings(@RequestBody Map<String, Object> in) {
        var ctx = admin();
        var mode = In.str(in, "mode");
        var totals = In.str(in, "totals");
        if (!"work".equals(mode) && !"status".equals(mode)) throw ApiException.field("mode", "계산 방식을 고르세요.");
        if (!"weighted".equals(totals) && !"simple_average".equals(totals)) throw ApiException.field("totals", "합계 방식을 고르세요.");
        boolean closed = Boolean.TRUE.equals(In.bool(in, "closedSets100"));
        settings.put("progress.mode", "\"" + mode + "\"", ctx.userId());
        settings.put("progress.closed_sets_100", String.valueOf(closed), ctx.userId());
        settings.put("progress.totals", "\"" + totals + "\"", ctx.userId());
        ctx.refresh();
        var p = ctx.s().settings().progress();
        db.execute("set local openpms.keep_updated_at = 'on'");
        int changed = 0;
        for (var w : db.selectFrom(WORK_PACKAGES).fetch()) {
            var status = ctx.s().status(w.getStatusId()).orElseThrow();
            var ratio = w.getDoneRatio() == null ? null : w.getDoneRatio().intValue();
            var v = com.openpms.wp.Progress.derive(p, status.isClosed(), status.defaultDoneRatio(), w.getWorkHours(), ratio, w.getRemainingHours(), false, "work".equals(mode));
            boolean sameRemaining = v.remainingHours() == null ? w.getRemainingHours() == null : w.getRemainingHours() != null && v.remainingHours().compareTo(w.getRemainingHours()) == 0;
            if (java.util.Objects.equals(v.doneRatio(), ratio) && sameRemaining) continue;
            w.setDoneRatio(v.doneRatio() == null ? null : v.doneRatio().shortValue());
            w.setRemainingHours(v.remainingHours());
            w.store();
            changed++;
        }
        return new Dto.ProgressSettings(p.mode(), p.closedSets100(), p.totals(), changed);
    }

    @GetMapping("/settings/calendar")
    public CalendarSettings calendar() {
        var ctx = admin();
        return new CalendarSettings(ctx.s().settings().workingDays(), ctx.s().settings().hoursPerDay());
    }

    @PutMapping("/settings/calendar")
    @Transactional
    public CalendarSettings saveCalendar(@RequestBody Map<String, Object> in) {
        var ctx = admin();
        var days = new ArrayList<Integer>();
        var raw = In.strings(in, "workingDays");
        if (raw != null) for (var d : raw) {
            try {
                int x = (int) Double.parseDouble(d);
                if (x >= 1 && x <= 7 && !days.contains(x)) days.add(x);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        days.sort(Integer::compare);
        if (days.isEmpty()) throw ApiException.field("workingDays", "근무 요일을 하루 이상 고르세요.");
        var hours = In.integer(in, "hoursPerDay", "hoursPerDay");
        if (hours == null || hours < 1 || hours > 24) throw ApiException.field("hoursPerDay", "1에서 24 사이로 입력하세요.");
        boolean changed = !days.equals(ctx.s().settings().workingDays());
        settings.put("calendar.working_days", days.toString(), ctx.userId());
        settings.put("calendar.hours_per_day", String.valueOf(hours), ctx.userId());
        if (changed) rescheduleAll(ctx);
        return new CalendarSettings(days, hours);
    }

    @GetMapping("/non-working-days")
    public List<Dto.NonWorkingDay> nonWorkingDays(@RequestParam int year) {
        admin();
        return db.selectFrom(NON_WORKING_DAYS).where(NON_WORKING_DAYS.DAY.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
                .orderBy(NON_WORKING_DAYS.DAY).fetch().stream().map(d -> new Dto.NonWorkingDay(d.getId(), d.getDay(), d.getName())).toList();
    }

    @PostMapping("/non-working-days")
    @Transactional
    public ResponseEntity<Void> addNonWorkingDay(@RequestBody Map<String, Object> in) {
        var ctx = admin();
        var date = In.date(in, "date", "date");
        if (date == null) throw ApiException.field("date", "날짜를 고르세요.");
        var n = name(in, true);
        if (db.fetchExists(NON_WORKING_DAYS, NON_WORKING_DAYS.DAY.eq(date))) throw ApiException.field("date", "이미 등록된 휴무일입니다.");
        db.insertInto(NON_WORKING_DAYS).set(NON_WORKING_DAYS.DAY, date).set(NON_WORKING_DAYS.NAME, n).execute();
        rescheduleAll(ctx);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/non-working-days/{id}")
    @Transactional
    public ResponseEntity<Void> removeNonWorkingDay(@PathVariable UUID id) {
        var ctx = admin();
        db.deleteFrom(NON_WORKING_DAYS).where(NON_WORKING_DAYS.ID.eq(id)).execute();
        rescheduleAll(ctx);
        return ResponseEntity.noContent().build();
    }
}

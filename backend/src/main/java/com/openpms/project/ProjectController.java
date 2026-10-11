package com.openpms.project;

import static com.openpms.db.Tables.CATEGORIES;
import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.MEMBER_ROLES;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.PROJECT_FAVORITES;
import static com.openpms.db.Tables.PROJECT_TYPES;
import static com.openpms.db.Tables.VERSIONS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.max;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.Html;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.core.Text;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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

/** Projects, members, categories, versions and principal search (architecture.md §4.2 프로젝트·멤버, F10). */
@RestController
@RequestMapping("/api/v1")
public class ProjectController {
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9-]{0,99}$");
    private static final Set<String> MODULES = Set.of("work_packages", "gantt", "boards", "calendar", "team_planner", "time_tracking");
    private static final Set<String> STATUSES = Set.of("on_track", "at_risk", "off_track");
    /** The seeded 프로젝트 관리자 role (fixed id, backend/scripts/ids.py) — a project's creator gets it. */
    static final List<String> OVERVIEW_WIDGETS = List.of("description", "workSummary", "status", "members", "versions", "dueSoon", "recent", "assignedToMe");
    static final UUID MANAGER_ROLE = UUID.fromString("0329397e-d933-5ac1-b27c-95889ba1a893");

    public record CategoryDto(UUID id, String name, Dto.UserRef defaultAssignee, long workPackageCount) {}

    public record VersionDto(UUID id, String name, String description, String status, LocalDate startDate, LocalDate endDate, long workPackageCount,
                             long closedCount) {}

    private final Current current;
    private final DSLContext db;
    private final ProjectCopy copier;

    public ProjectController(Current current, DSLContext db, ProjectCopy copier) {
        this.copier = copier;
        this.current = current;
        this.db = db;
    }

    /* ---------- DTOs ---------- */

    private Map<UUID, OffsetDateTime> latestActivity(Collection<UUID> projectIds) {
        var out = new HashMap<UUID, OffsetDateTime>();
        if (projectIds.isEmpty()) return out;
        db.select(WORK_PACKAGES.PROJECT_ID, max(JOURNALS.UPDATED_AT)).from(JOURNALS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(JOURNALS.WORK_PACKAGE_ID))
                .where(WORK_PACKAGES.PROJECT_ID.in(projectIds)).groupBy(WORK_PACKAGES.PROJECT_ID).fetch().forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }

    private List<Dto.Project> toDtos(Ctx ctx, List<ProjectRow> projects) {
        var s = ctx.s();
        var favs = new HashSet<>(db.select(PROJECT_FAVORITES.PROJECT_ID).from(PROJECT_FAVORITES).where(PROJECT_FAVORITES.USER_ID.eq(ctx.userId())).fetch(PROJECT_FAVORITES.PROJECT_ID));
        var activity = latestActivity(projects.stream().map(ProjectRow::id).toList());
        return projects.stream().map(p -> {
            var parent = p.parentId() == null ? null : s.project(p.parentId()).map(Refs::project).orElse(null);
            var latest = activity.get(p.id());
            if (latest == null || latest.isBefore(p.updatedAt())) latest = p.updatedAt();
            return new Dto.Project(p.id(), p.identifier(), p.name(), p.descriptionHtml(), p.status(), p.statusExplanation(), p.isPublic(), p.isTemplate(),
                    p.archivedAt(), parent, p.enabledModules(), favs.contains(p.id()), List.copyOf(ctx.perms(p)), p.createdAt(), p.updatedAt(), latest,
                    p.internalComments(), p.overviewWidgets());
        }).toList();
    }

    private Dto.Project toDto(Ctx ctx, ProjectRow p) {
        return toDtos(ctx, List.of(p)).get(0);
    }

    /* ---------- projects ---------- */

    @GetMapping("/projects")
    public Dto.Page<Dto.Project> list(@RequestParam(defaultValue = "active") String filter, @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int pageSize) {
        var ctx = current.ctx();
        var s = ctx.s();
        var favs = new HashSet<>(db.select(PROJECT_FAVORITES.PROJECT_ID).from(PROJECT_FAVORITES).where(PROJECT_FAVORITES.USER_ID.eq(ctx.userId())).fetch(PROJECT_FAVORITES.PROJECT_ID));
        var needle = q == null ? "" : q.trim().toLowerCase();
        var list = ctx.visibleProjects("project.view").stream()
                .filter(p -> "archived".equals(filter) ? p.archivedAt() != null : p.archivedAt() == null)
                .filter(p -> !"templates".equals(filter) || p.isTemplate())
                .filter(p -> !"mine".equals(filter) || s.isMember(ctx.userId(), p.id()))
                .filter(p -> !"favorite".equals(filter) || favs.contains(p.id()))
                .filter(p -> status == null || status.isBlank() || status.equals(p.status()))
                .filter(p -> needle.isEmpty() || p.name().toLowerCase().contains(needle) || p.identifier().contains(needle))
                .sorted(Comparator.comparing(ProjectRow::name, Text.KOREAN)).toList();
        page = Math.max(1, page);
        pageSize = Math.min(100, Math.max(1, pageSize));
        int from = Math.min(list.size(), (page - 1) * pageSize);
        return new Dto.Page<>(toDtos(ctx, list.subList(from, Math.min(list.size(), from + pageSize))), list.size(), page, pageSize);
    }

    @GetMapping("/projects/{id}")
    public Dto.Project get(@PathVariable String id) {
        var ctx = current.ctx();
        return toDto(ctx, ctx.findProject(id));
    }

    /**
     * A blank project, or one from a template ({templateId, copy: [members, versions, categories, workPackages, boards, queries]}):
     * the template must be one the person can see; what is always and optionally taken over is in {@link ProjectCopy}.
     */
    @PostMapping("/projects")
    @Transactional
    public ResponseEntity<Dto.Project> create(@RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        ProjectRow template = null;
        var templateId = In.str(in, "templateId");
        if (templateId != null && !templateId.isBlank()) {
            template = ctx.visibleProjects("project.view").stream().filter(p -> p.isTemplate() && p.archivedAt() == null)
                    .filter(p -> p.id().toString().equals(templateId) || p.identifier().equals(templateId)).findFirst()
                    .orElseThrow(() -> ApiException.field("template", "템플릿을 찾을 수 없습니다."));
        }
        return make(ctx, in, template);
    }

    /**
     * 프로젝트 복사: a new project from this one, as from a template ({name, identifier, parentId?, copy: [...]}); it takes the
     * right to edit this project besides the right to create projects.
     */
    @PostMapping("/projects/{id}/copy")
    @Transactional
    public ResponseEntity<Dto.Project> copy(@PathVariable String id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var source = ctx.findProject(id);
        ctx.require(source, "project.edit", "이 프로젝트를 복사할 권한이 없습니다.");
        return make(ctx, in, source);
    }

    private ResponseEntity<Dto.Project> make(Ctx ctx, Map<String, Object> in, ProjectRow template) {
        if (!ctx.isAdmin() && !ctx.globalPermissions().contains("global.create_projects")) throw ApiException.forbidden("프로젝트를 만들 권한이 없습니다.");
        var parts = new HashSet<>(In.strings(in, "copy") == null ? List.<String>of() : In.strings(in, "copy"));
        if (!ProjectCopy.PARTS.containsAll(parts)) throw ApiException.field("copy", "복사할 수 없는 항목이 있습니다.");
        var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (name.isEmpty()) throw ApiException.field("name", "프로젝트 이름을 입력하세요.");
        if (name.length() > 255) throw ApiException.field("name", "프로젝트 이름은 255자 이하로 입력하세요.");
        var identifier = In.str(in, "identifier") == null ? "" : In.str(in, "identifier").trim();
        if (!IDENTIFIER.matcher(identifier).matches()) throw ApiException.field("identifier", "영문 소문자로 시작하고 영문 소문자, 숫자, 하이픈만 쓸 수 있습니다.");
        if (db.fetchExists(PROJECTS, PROJECTS.IDENTIFIER.eq(identifier))) throw ApiException.field("identifier", "이미 사용 중인 식별자입니다.");
        var parentId = In.uuid(in, "parentId", "parent");
        if (parentId != null) {
            var parent = ctx.s().project(parentId).orElse(null);
            if (parent == null || !ctx.can(parent, "project.edit")) throw ApiException.field("parent", "상위 프로젝트를 찾을 수 없습니다.");
        }
        var html = Html.sanitize(In.str(in, "descriptionHtml"));
        var p = db.newRecord(PROJECTS);
        p.setIdentifier(identifier);
        p.setName(name);
        p.setDescriptionHtml(html);
        p.setDescriptionText(Html.text(html));
        p.setIsPublic(Boolean.TRUE.equals(In.bool(in, "isPublic")));
        p.setParentId(parentId);
        p.setEnabledModules(new String[] {"work_packages", "gantt", "boards", "time_tracking"});
        p.setCreatedBy(ctx.userId());
        p.store();
        for (var t : ctx.s().types()) if (t.isDefault()) db.insertInto(PROJECT_TYPES).set(PROJECT_TYPES.PROJECT_ID, p.getId()).set(PROJECT_TYPES.TYPE_ID, t.id()).execute();
        var m = db.insertInto(MEMBERS).set(MEMBERS.PROJECT_ID, p.getId()).set(MEMBERS.USER_ID, ctx.userId()).returning(MEMBERS.ID).fetchOne();
        db.insertInto(MEMBER_ROLES).set(MEMBER_ROLES.MEMBER_ID, m.getId()).set(MEMBER_ROLES.ROLE_ID, MANAGER_ROLE).execute();
        if (template != null) copier.copy(ctx, template, p.getId(), parts, !html.isBlank());
        ctx.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(ctx, ctx.findProject(p.getId())));
    }

    @PatchMapping("/projects/{id}")
    @Transactional
    public Dto.Project update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        ctx.require(p, In.has(patch, "enabledModules") ? "project.manage_modules" : "project.edit");
        if (In.has(patch, "enabledModules") && patch.keySet().stream().anyMatch(k -> !k.equals("enabledModules"))) ctx.require(p, "project.edit");
        var u = db.update(PROJECTS).set(PROJECTS.UPDATED_AT, OffsetDateTime.now());
        if (In.has(patch, "name")) {
            var name = In.str(patch, "name") == null ? "" : In.str(patch, "name").trim();
            if (name.isEmpty()) throw ApiException.field("name", "프로젝트 이름을 입력하세요.");
            u = u.set(PROJECTS.NAME, name);
        }
        if (In.has(patch, "descriptionHtml")) {
            var html = Html.sanitize(In.str(patch, "descriptionHtml"));
            u = u.set(PROJECTS.DESCRIPTION_HTML, html).set(PROJECTS.DESCRIPTION_TEXT, Html.text(html));
        }
        if (In.has(patch, "status")) {
            var st = In.str(patch, "status");
            if (st != null && !STATUSES.contains(st)) throw ApiException.field("status", "알 수 없는 상태입니다.");
            u = u.set(PROJECTS.STATUS, st);
        }
        if (In.has(patch, "statusExplanation")) u = u.set(PROJECTS.STATUS_EXPLANATION, In.str(patch, "statusExplanation") == null ? "" : In.str(patch, "statusExplanation"));
        if (In.has(patch, "isPublic")) u = u.set(PROJECTS.IS_PUBLIC, Boolean.TRUE.equals(In.bool(patch, "isPublic")));
        // 개요 위젯 (S05): which widgets show, in order
        if (In.has(patch, "overviewWidgets")) {
            var widgets = In.strings(patch, "overviewWidgets");
            if (widgets == null || !OVERVIEW_WIDGETS.containsAll(widgets)) throw ApiException.field("overviewWidgets", "알 수 없는 위젯이 있습니다.");
            u = u.set(PROJECTS.OVERVIEW_WIDGETS, widgets.stream().distinct().toArray(String[]::new));
        }
        // 내부 코멘트: comments only roles with wp.view_internal_comments read
        if (In.has(patch, "internalComments")) u = u.set(PROJECTS.INTERNAL_COMMENTS, Boolean.TRUE.equals(In.bool(patch, "internalComments")));
        // 템플릿으로 쓰기: offered when someone creates a project
        if (In.has(patch, "isTemplate")) u = u.set(PROJECTS.IS_TEMPLATE, Boolean.TRUE.equals(In.bool(patch, "isTemplate")));
        // 상위 프로젝트: a project the person may edit, never the project itself or one below it; null makes it a top project
        if (In.has(patch, "parentId")) {
            var parentId = In.uuid(patch, "parentId", "parent");
            if (parentId != null) {
                var parent = ctx.s().project(parentId).orElse(null);
                if (parent == null || !ctx.can(parent, "project.edit")) throw ApiException.field("parent", "상위 프로젝트를 찾을 수 없습니다.");
                for (var a = parent; a != null; a = a.parentId() == null ? null : ctx.s().project(a.parentId()).orElse(null)) {
                    if (a.id().equals(p.id())) throw ApiException.field("parent", "자기 자신이나 하위 프로젝트를 상위로 정할 수 없습니다.");
                }
            }
            u = u.set(PROJECTS.PARENT_ID, parentId);
        }
        if (In.has(patch, "enabledModules")) {
            var mods = In.strings(patch, "enabledModules");
            if (mods == null || !MODULES.containsAll(mods)) throw ApiException.field("enabledModules", "알 수 없는 모듈입니다.");
            u = u.set(PROJECTS.ENABLED_MODULES, mods.stream().distinct().toArray(String[]::new));
        }
        u.where(PROJECTS.ID.eq(p.id())).execute();
        ctx.refresh();
        return toDto(ctx, ctx.findProject(p.id()));
    }

    @PutMapping("/projects/{id}/favorite")
    @Transactional
    public ResponseEntity<Void> favorite(@PathVariable String id) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        db.insertInto(PROJECT_FAVORITES).set(PROJECT_FAVORITES.USER_ID, ctx.userId()).set(PROJECT_FAVORITES.PROJECT_ID, p.id()).onConflictDoNothing().execute();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/projects/{id}/favorite")
    @Transactional
    public ResponseEntity<Void> unfavorite(@PathVariable String id) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        db.deleteFrom(PROJECT_FAVORITES).where(PROJECT_FAVORITES.USER_ID.eq(ctx.userId()), PROJECT_FAVORITES.PROJECT_ID.eq(p.id())).execute();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{id}/overview")
    public Dto.ProjectOverview overview(@PathVariable String id) {
        var ctx = current.ctx();
        var s = ctx.s();
        var p = ctx.findProject(id);
        var counts = db.select(WORK_PACKAGES.STATUS_ID, count()).from(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(p.id()))
                .groupBy(WORK_PACKAGES.STATUS_ID).fetchMap(WORK_PACKAGES.STATUS_ID, count());
        var closed = s.statuses().stream().filter(Snapshot.StatusRow::isClosed).map(Snapshot.StatusRow::id).toList();
        long open = counts.entrySet().stream().filter(e -> !closed.contains(e.getKey())).mapToLong(Map.Entry::getValue).sum();
        long overdue = db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(p.id()).and(WORK_PACKAGES.DUE_DATE.lt(ctx.today()))
                .and(closed.isEmpty() ? org.jooq.impl.DSL.trueCondition() : WORK_PACKAGES.STATUS_ID.notIn(closed)));
        var members = s.members().stream().filter(m -> m.projectId().equals(p.id()))
                .map(m -> new Dto.OverviewMember(principalOf(s, m), s.roles().stream().filter(r -> m.roleIds().contains(r.id())).map(Snapshot.RoleRow::name).toList()))
                .toList();
        var byStatus = s.statuses().stream().filter(x -> counts.getOrDefault(x.id(), 0) > 0)
                .map(x -> new Dto.StatusCount(Refs.status(x), counts.get(x.id()))).toList();
        return new Dto.ProjectOverview(toDto(ctx, p), members, byStatus, open, overdue);
    }

    @PutMapping("/projects/{id}/types")
    @Transactional
    public Dto.Project setTypes(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var s = ctx.s();
        var p = ctx.findProject(id);
        ctx.require(p, "project.manage_types", "프로젝트 유형을 바꿀 권한이 없습니다.");
        var typeIds = In.uuids(body, "typeIds", "types").stream().filter(t -> s.type(t).isPresent()).distinct().toList();
        if (typeIds.isEmpty()) throw ApiException.field("types", "유형을 하나 이상 고르세요.");
        var inUse = s.types().stream().filter(t -> !typeIds.contains(t.id())
                && db.fetchExists(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(p.id()).and(WORK_PACKAGES.TYPE_ID.eq(t.id())))).map(Snapshot.TypeRow::name).toList();
        if (!inUse.isEmpty()) throw ApiException.unprocessable("쓰고 있는 유형은 끌 수 없습니다.", String.join(", ", inUse) + " 유형의 작업이 이 프로젝트에 있습니다.");
        db.deleteFrom(PROJECT_TYPES).where(PROJECT_TYPES.PROJECT_ID.eq(p.id())).execute();
        for (var t : typeIds) db.insertInto(PROJECT_TYPES).set(PROJECT_TYPES.PROJECT_ID, p.id()).set(PROJECT_TYPES.TYPE_ID, t).execute();
        db.update(PROJECTS).set(PROJECTS.UPDATED_AT, OffsetDateTime.now()).where(PROJECTS.ID.eq(p.id())).execute();
        ctx.refresh();
        return toDto(ctx, ctx.findProject(p.id()));
    }

    @PostMapping("/projects/{id}/archive")
    @Transactional
    public ResponseEntity<Void> archive(@PathVariable String id) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        ctx.require(p, "project.archive", "프로젝트를 보관할 권한이 없습니다.");
        db.update(PROJECTS).set(PROJECTS.ARCHIVED_AT, OffsetDateTime.now()).where(PROJECTS.ID.eq(p.id())).execute();
        return ResponseEntity.noContent().build();
    }

    /** Back from the archive (administrators). A subproject waits for its archived parent. */
    @PostMapping("/projects/{id}/unarchive")
    @Transactional
    public ResponseEntity<Void> unarchive(@PathVariable String id) {
        var ctx = current.ctx();
        var p = ctx.findProject(id); // first: an invisible project is a 404, not a 403
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 프로젝트를 복원할 수 있습니다.");
        var parent = p.parentId() == null ? null : ctx.s().projects().get(p.parentId());
        if (parent != null && parent.archivedAt() != null)
            throw new ApiException(409, "상위 프로젝트가 보관되어 있습니다.", "'" + parent.name() + "'을(를) 먼저 복원하세요.");
        db.update(PROJECTS).setNull(PROJECTS.ARCHIVED_AT).set(PROJECTS.UPDATED_AT, OffsetDateTime.now()).where(PROJECTS.ID.eq(p.id())).execute();
        return ResponseEntity.noContent().build();
    }

    public record DeleteProject(String confirmIdentifier) {}

    /**
     * Deletes a project and everything in it (administrators; the identifier typed again). Subprojects block it. Items elsewhere
     * keep going: a child in another project loses its parent, relations to them go.
     */
    @DeleteMapping("/projects/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestBody(required = false) DeleteProject in) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 프로젝트를 삭제할 수 있습니다.");
        if (in == null || in.confirmIdentifier() == null || !p.identifier().equals(in.confirmIdentifier().trim()))
            throw ApiException.field("confirmIdentifier", "식별자 '" + p.identifier() + "'를 그대로 입력하세요.");
        var children = ctx.s().projects().values().stream().filter(c -> p.id().equals(c.parentId())).map(ProjectRow::name).sorted().toList();
        if (!children.isEmpty())
            throw new ApiException(409, "하위 프로젝트가 있습니다.", "먼저 하위 프로젝트를 옮기거나 삭제하세요: " + String.join(", ", children));
        var inside = DSL.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(p.id()));
        db.update(WORK_PACKAGES).setNull(WORK_PACKAGES.PARENT_ID)
                .where(WORK_PACKAGES.PARENT_ID.in(inside), WORK_PACKAGES.PROJECT_ID.ne(p.id())).execute();
        db.deleteFrom(PROJECTS).where(PROJECTS.ID.eq(p.id())).execute();
        return ResponseEntity.noContent().build();
    }

    /* ---------- members ---------- */

    static Dto.IdName principalOf(Snapshot s, Snapshot.MemberRow m) {
        if (m.groupId() != null) return new Dto.IdName(m.groupId(), s.groups().containsKey(m.groupId()) ? s.groups().get(m.groupId()).name() : "삭제된 그룹");
        var u = Refs.user(s, m.userId());
        return new Dto.IdName(u.id(), u.name());
    }

    static Dto.Member toMember(Snapshot s, Snapshot.MemberRow m) {
        var who = principalOf(s, m);
        var email = m.userId() == null ? null : s.user(m.userId()).map(Snapshot.UserRow::email).orElse(null);
        return new Dto.Member(m.id(), new Dto.Principal(who.id(), who.name(), m.groupId() != null ? "group" : "user", email),
                s.roles().stream().filter(r -> m.roleIds().contains(r.id())).map(r -> new Dto.IdName(r.id(), r.name())).toList(), m.createdAt());
    }

    @GetMapping("/projects/{id}/members")
    public List<Dto.Member> members(@PathVariable String id) {
        var ctx = current.ctx();
        var s = ctx.s();
        var p = ctx.findProject(id);
        ctx.require(p, "project.view_members");
        return s.members().stream().filter(m -> m.projectId().equals(p.id())).map(m -> toMember(s, m))
                .sorted(Comparator.comparing(m -> m.principal().name(), Text.KOREAN)).toList();
    }

    @PostMapping("/projects/{id}/members")
    @Transactional
    public ResponseEntity<List<Dto.Member>> addMembers(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var s = ctx.s();
        var p = ctx.findProject(id);
        ctx.require(p, "project.manage_members");
        var roleIds = roles(s, In.uuids(body, "roleIds", "roles"));
        var added = new ArrayList<UUID>();
        for (var principal : In.uuids(body, "principalIds", "principals")) {
            boolean exists = s.members().stream().anyMatch(m -> m.projectId().equals(p.id()) && (principal.equals(m.userId()) || principal.equals(m.groupId())));
            if (exists) continue;
            boolean group = s.groups().containsKey(principal);
            if (!group && s.user(principal).filter(u -> !"deleted".equals(u.status())).isEmpty()) throw ApiException.notFound("사용자나 그룹을 찾을 수 없습니다.");
            var m = db.insertInto(MEMBERS).set(MEMBERS.PROJECT_ID, p.id()).set(group ? MEMBERS.GROUP_ID : MEMBERS.USER_ID, principal).returning(MEMBERS.ID).fetchOne();
            for (var r : roleIds) db.insertInto(MEMBER_ROLES).set(MEMBER_ROLES.MEMBER_ID, m.getId()).set(MEMBER_ROLES.ROLE_ID, r).execute();
            added.add(m.getId());
        }
        ctx.refresh();
        var s2 = ctx.s();
        return ResponseEntity.status(HttpStatus.CREATED).body(s2.members().stream().filter(m -> added.contains(m.id())).map(m -> toMember(s2, m)).toList());
    }

    private static List<UUID> roles(Snapshot s, List<UUID> roleIds) {
        var valid = roleIds.stream().filter(r -> s.role(r).filter(x -> "project".equals(x.scope()) && x.builtin() == null).isPresent()).distinct().toList();
        if (valid.isEmpty()) throw ApiException.field("roles", "역할을 하나 이상 고르세요.");
        return valid;
    }

    private Snapshot.MemberRow member(Ctx ctx, UUID memberId) {
        var m = ctx.s().members().stream().filter(x -> x.id().equals(memberId)).findFirst().orElseThrow(() -> ApiException.notFound("멤버를 찾을 수 없습니다."));
        var p = ctx.findProject(m.projectId());
        ctx.require(p, "project.manage_members");
        return m;
    }

    /** Is m the project's only member holding the manager role? (S23 last-manager guard) */
    private static boolean lastManager(Snapshot s, Snapshot.MemberRow m) {
        var managers = s.members().stream().filter(x -> x.projectId().equals(m.projectId()) && x.roleIds().contains(MANAGER_ROLE)).toList();
        return managers.size() == 1 && managers.get(0).id().equals(m.id());
    }

    @PatchMapping("/members/{memberId}")
    @Transactional
    public Dto.Member updateMember(@PathVariable UUID memberId, @RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var m = member(ctx, memberId);
        var roleIds = roles(ctx.s(), In.uuids(body, "roleIds", "roles"));
        if (lastManager(ctx.s(), m) && !roleIds.contains(MANAGER_ROLE)) throw ApiException.unprocessable("마지막 프로젝트 관리자입니다.", "다른 멤버를 관리자로 지정한 뒤 바꾸세요.");
        db.deleteFrom(MEMBER_ROLES).where(MEMBER_ROLES.MEMBER_ID.eq(memberId)).execute();
        for (var r : roleIds) db.insertInto(MEMBER_ROLES).set(MEMBER_ROLES.MEMBER_ID, memberId).set(MEMBER_ROLES.ROLE_ID, r).execute();
        ctx.refresh();
        var s = ctx.s();
        return s.members().stream().filter(x -> x.id().equals(memberId)).findFirst().map(x -> toMember(s, x)).orElseThrow();
    }

    @DeleteMapping("/members/{memberId}")
    @Transactional
    public ResponseEntity<Void> removeMember(@PathVariable UUID memberId) {
        var ctx = current.ctx();
        var m = member(ctx, memberId);
        if (lastManager(ctx.s(), m)) throw ApiException.unprocessable("마지막 프로젝트 관리자는 뺄 수 없습니다.", null);
        db.deleteFrom(MEMBERS).where(MEMBERS.ID.eq(memberId)).execute();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/principals")
    public List<Dto.PrincipalHit> principals(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String projectId,
                                             @RequestParam(defaultValue = "user") String kind) {
        var ctx = current.ctx();
        var s = ctx.s();
        var needle = q.trim().toLowerCase();
        var p = projectId == null || projectId.isBlank() ? null : ctx.findProject(projectId);
        var people = s.users().values().stream().filter(u -> "active".equals(u.status()))
                .filter(u -> p == null || s.isMember(u.id(), p.id()))
                .filter(u -> needle.isEmpty() || u.name().toLowerCase().contains(needle) || u.email().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Snapshot.UserRow::name, Text.KOREAN)).limit(20)
                .map(u -> new Dto.PrincipalHit(u.id(), u.name(), "user")).toList();
        var out = new ArrayList<Dto.PrincipalHit>();
        if ("all".equals(kind) && p == null) {
            s.groups().values().stream().filter(g -> needle.isEmpty() || g.name().toLowerCase().contains(needle))
                    .forEach(g -> out.add(new Dto.PrincipalHit(g.id(), g.name(), "group")));
        }
        out.addAll(people);
        return out;
    }

    /* ---------- categories ---------- */

    private List<CategoryDto> categoryDtos(Ctx ctx, UUID projectId, UUID only) {
        var s = ctx.s();
        var counts = db.select(WORK_PACKAGES.CATEGORY_ID, count()).from(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(projectId), WORK_PACKAGES.CATEGORY_ID.isNotNull())
                .groupBy(WORK_PACKAGES.CATEGORY_ID).fetchMap(WORK_PACKAGES.CATEGORY_ID, count());
        return db.selectFrom(CATEGORIES).where(CATEGORIES.PROJECT_ID.eq(projectId)).fetch().stream()
                .filter(c -> only == null || c.getId().equals(only))
                .map(c -> new CategoryDto(c.getId(), c.getName(), Refs.user(s, c.getDefaultAssigneeId()), counts.getOrDefault(c.getId(), 0)))
                .sorted(Comparator.comparing(CategoryDto::name, Text.KOREAN)).toList();
    }

    private static String checkName(Map<String, Object> in, boolean required) {
        if (!In.has(in, "name") && !required) return null;
        var n = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (n.isEmpty()) throw ApiException.field("name", "이름을 입력하세요.");
        if (n.length() > 100) throw ApiException.field("name", "이름은 100자 이하로 입력하세요.");
        return n;
    }

    @GetMapping("/projects/{id}/categories")
    public List<CategoryDto> categories(@PathVariable String id) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        ctx.require(p, "wp.view");
        return categoryDtos(ctx, p.id(), null);
    }

    @PostMapping("/projects/{id}/categories")
    @Transactional
    public ResponseEntity<CategoryDto> createCategory(@PathVariable String id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        ctx.require(p, "project.manage_categories", "카테고리를 관리할 권한이 없습니다.");
        var name = checkName(in, true);
        if (db.fetchExists(CATEGORIES, CATEGORIES.PROJECT_ID.eq(p.id()).and(CATEGORIES.NAME.eq(name)))) throw ApiException.field("name", "같은 이름이 이미 있습니다.");
        var assignee = In.uuid(in, "defaultAssigneeId", "defaultAssignee");
        if (assignee != null && !ctx.s().isMember(assignee, p.id())) throw ApiException.field("defaultAssignee", "프로젝트 멤버만 지정할 수 있습니다.");
        var c = db.insertInto(CATEGORIES).set(CATEGORIES.PROJECT_ID, p.id()).set(CATEGORIES.NAME, name).set(CATEGORIES.DEFAULT_ASSIGNEE_ID, assignee)
                .returning(CATEGORIES.ID).fetchOne();
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryDtos(ctx, p.id(), c.getId()).get(0));
    }

    private ProjectRow categoryProject(Ctx ctx, UUID id) {
        var c = db.selectFrom(CATEGORIES).where(CATEGORIES.ID.eq(id)).fetchOne();
        if (c == null) throw ApiException.notFound("카테고리를 찾을 수 없습니다.");
        var p = ctx.findProject(c.getProjectId());
        ctx.require(p, "project.manage_categories", "카테고리를 관리할 권한이 없습니다.");
        return p;
    }

    @PatchMapping("/categories/{id}")
    @Transactional
    public CategoryDto updateCategory(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var p = categoryProject(ctx, id);
        var name = checkName(in, false);
        var u = db.update(CATEGORIES).set(CATEGORIES.UPDATED_AT, OffsetDateTime.now());
        if (name != null) {
            if (db.fetchExists(CATEGORIES, CATEGORIES.PROJECT_ID.eq(p.id()).and(CATEGORIES.NAME.eq(name)).and(CATEGORIES.ID.ne(id)))) throw ApiException.field("name", "같은 이름이 이미 있습니다.");
            u = u.set(CATEGORIES.NAME, name);
        }
        if (In.has(in, "defaultAssigneeId")) {
            var assignee = In.uuid(in, "defaultAssigneeId", "defaultAssignee");
            if (assignee != null && !ctx.s().isMember(assignee, p.id())) throw ApiException.field("defaultAssignee", "프로젝트 멤버만 지정할 수 있습니다.");
            u = u.set(CATEGORIES.DEFAULT_ASSIGNEE_ID, assignee);
        }
        u.where(CATEGORIES.ID.eq(id)).execute();
        return categoryDtos(ctx, p.id(), id).get(0);
    }

    @DeleteMapping("/categories/{id}")
    @Transactional
    public ResponseEntity<Void> removeCategory(@PathVariable UUID id) {
        var ctx = current.ctx();
        categoryProject(ctx, id);
        db.deleteFrom(CATEGORIES).where(CATEGORIES.ID.eq(id)).execute(); // work_packages.category_id: on delete set null
        return ResponseEntity.noContent().build();
    }

    /* ---------- versions ---------- */

    private List<VersionDto> versionDtos(Ctx ctx, UUID projectId, UUID only) {
        var s = ctx.s();
        var closed = new HashSet<UUID>();
        s.statuses().stream().filter(Snapshot.StatusRow::isClosed).forEach(x -> closed.add(x.id()));
        var total = new HashMap<UUID, Long>();
        var done = new HashMap<UUID, Long>();
        db.select(WORK_PACKAGES.VERSION_ID, WORK_PACKAGES.STATUS_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.VERSION_ID.isNotNull()).fetch().forEach(r -> {
            total.merge(r.value1(), 1L, Long::sum);
            if (closed.contains(r.value2())) done.merge(r.value1(), 1L, Long::sum);
        });
        return db.selectFrom(VERSIONS).where(VERSIONS.PROJECT_ID.eq(projectId)).fetch().stream()
                .filter(v -> only == null || v.getId().equals(only))
                .map(v -> new VersionDto(v.getId(), v.getName(), v.getDescription(), v.getStatus(), v.getStartDate(), v.getEndDate(), total.getOrDefault(v.getId(), 0L),
                        done.getOrDefault(v.getId(), 0L)))
                .sorted(Comparator.comparing((VersionDto v) -> v.endDate() == null ? LocalDate.MAX : v.endDate())).toList();
    }

    private void checkVersionDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) throw ApiException.field("endDate", "종료일은 시작일 이후여야 합니다.");
    }

    @GetMapping("/projects/{id}/versions")
    public List<VersionDto> versions(@PathVariable String id) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        ctx.require(p, "wp.view");
        return versionDtos(ctx, p.id(), null);
    }

    @PostMapping("/projects/{id}/versions")
    @Transactional
    public ResponseEntity<VersionDto> createVersion(@PathVariable String id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var p = ctx.findProject(id);
        ctx.require(p, "project.manage_versions", "버전을 관리할 권한이 없습니다.");
        var name = checkName(in, true);
        if (db.fetchExists(VERSIONS, VERSIONS.PROJECT_ID.eq(p.id()).and(VERSIONS.NAME.eq(name)))) throw ApiException.field("name", "같은 이름이 이미 있습니다.");
        var start = In.date(in, "startDate", "startDate");
        var end = In.date(in, "endDate", "endDate");
        checkVersionDates(start, end);
        var status = In.str(in, "status") == null ? "open" : In.str(in, "status");
        if (!Set.of("open", "locked", "closed").contains(status)) throw ApiException.field("status", "알 수 없는 상태입니다.");
        var v = db.insertInto(VERSIONS).set(VERSIONS.PROJECT_ID, p.id()).set(VERSIONS.NAME, name)
                .set(VERSIONS.DESCRIPTION, In.str(in, "description") == null ? "" : In.str(in, "description")).set(VERSIONS.STATUS, status)
                .set(VERSIONS.START_DATE, start).set(VERSIONS.END_DATE, end).returning(VERSIONS.ID).fetchOne();
        return ResponseEntity.status(HttpStatus.CREATED).body(versionDtos(ctx, p.id(), v.getId()).get(0));
    }

    private com.openpms.db.tables.records.VersionsRecord versionFor(Ctx ctx, UUID id) {
        var v = db.selectFrom(VERSIONS).where(VERSIONS.ID.eq(id)).fetchOne();
        if (v == null) throw ApiException.notFound("버전을 찾을 수 없습니다.");
        ctx.require(ctx.findProject(v.getProjectId()), "project.manage_versions", "버전을 관리할 권한이 없습니다.");
        return v;
    }

    @PatchMapping("/versions/{id}")
    @Transactional
    public VersionDto updateVersion(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var v = versionFor(ctx, id);
        var name = checkName(in, false);
        if (name != null) {
            if (db.fetchExists(VERSIONS, VERSIONS.PROJECT_ID.eq(v.getProjectId()).and(VERSIONS.NAME.eq(name)).and(VERSIONS.ID.ne(id)))) throw ApiException.field("name", "같은 이름이 이미 있습니다.");
            v.setName(name);
        }
        if (In.has(in, "description")) v.setDescription(In.str(in, "description") == null ? "" : In.str(in, "description"));
        if (In.has(in, "status")) {
            var st = In.str(in, "status");
            if (!Set.of("open", "locked", "closed").contains(st)) throw ApiException.field("status", "알 수 없는 상태입니다.");
            v.setStatus(st);
        }
        if (In.has(in, "startDate")) v.setStartDate(In.date(in, "startDate", "startDate"));
        if (In.has(in, "endDate")) v.setEndDate(In.date(in, "endDate", "endDate"));
        checkVersionDates(v.getStartDate(), v.getEndDate());
        v.store();
        return versionDtos(ctx, v.getProjectId(), id).get(0);
    }

    @DeleteMapping("/versions/{id}")
    @Transactional
    public ResponseEntity<Void> removeVersion(@PathVariable UUID id) {
        var ctx = current.ctx();
        versionFor(ctx, id).delete(); // work_packages.version_id: on delete set null
        return ResponseEntity.noContent().build();
    }
}

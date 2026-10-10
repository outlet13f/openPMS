package com.openpms.query;

import static com.openpms.db.Tables.TYPES;
import static com.openpms.db.Tables.QUERIES;
import static com.openpms.db.Tables.QUERY_FAVORITES;

import com.openpms.api.Dto;
import com.openpms.customfield.CustomFields;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.QueriesRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The list engine's routes: run a query, filter options, saved views (F03). */
@RestController
@RequestMapping("/api/v1")
public class QueryController {
    private static final List<String> DEFAULT_COLUMNS = List.of("number", "subject", "type", "status", "assignee", "priority", "dueDate");
    private static final Set<String> VIEWS = Set.of("table", "gantt", "calendar", "team_planner");

    private final Current current;
    private final QueryEngine engine;
    private final DSLContext db;
    private final JsonMapper json;

    private final CustomFields customFields;

    public QueryController(Current current, QueryEngine engine, DSLContext db, JsonMapper json, CustomFields customFields) {
        this.customFields = customFields;
        this.current = current;
        this.engine = engine;
        this.db = db;
        this.json = json;
    }

    /* ---------- running a query ---------- */

    @SuppressWarnings("unchecked")
    static QueryEngine.Spec parse(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw ApiException.unprocessable("보기 설정을 확인하세요.", "spec이 없습니다.");
        var filters = new ArrayList<QueryEngine.Filter>();
        if (m.get("filters") instanceof List<?> fs) for (var f : fs) {
            if (!(f instanceof Map<?, ?> x)) continue;
            List<String> values = x.get("values") instanceof List<?> l ? l.stream().map(v -> v == null ? null : String.valueOf(v)).toList() : List.of();
            filters.add(new QueryEngine.Filter(String.valueOf(x.get("field")), String.valueOf(x.get("op")), values));
        }
        var sort = new ArrayList<QueryEngine.Sort>();
        if (m.get("sort") instanceof List<?> ss) for (var s : ss) {
            if (s instanceof Map<?, ?> x) sort.add(new QueryEngine.Sort(String.valueOf(x.get("field")), String.valueOf(x.get("dir"))));
        }
        boolean timeline = m.get("timeline") instanceof Map<?, ?> t && Boolean.TRUE.equals(t.get("visible"));
        return new QueryEngine.Spec(filters, sort, Boolean.TRUE.equals(m.get("hierarchy")), !Boolean.FALSE.equals(m.get("includeSubprojects")),
                m.get("text") == null ? null : String.valueOf(m.get("text")), timeline);
    }

    @PostMapping("/work-packages/query")
    public Dto.QueryResult query(@RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var projectId = In.str(body, "projectId");
        var project = projectId == null || projectId.isBlank() ? null : ctx.findProject(projectId);
        var page = In.integer(body, "page", "page");
        var size = In.integer(body, "pageSize", "pageSize");
        return engine.run(ctx, parse(body.get("spec")), project, page == null ? 1 : page, size == null ? 20 : size);
    }

    @GetMapping("/work-packages/filters")
    public Dto.FilterOptions filterOptions(@RequestParam(required = false) String projectId) {
        var ctx = current.ctx();
        var s = ctx.s();
        var p = projectId == null || projectId.isBlank() ? null : ctx.findProject(projectId);
        var scope = ctx.visibleProjects("wp.view").stream().filter(x -> p == null || x.id().equals(p.id()) || s.isDescendant(x.id(), p.id())).toList();
        List<Dto.UserRef> users = (p != null ? s.projectUsers(p.id()) : s.users().values().stream().filter(u -> "active".equals(u.status()))
                .sorted((a, b) -> com.openpms.core.Text.KOREAN.compare(a.name(), b.name())).toList())
                .stream().map(u -> new Dto.UserRef(u.id(), u.name())).toList();
        return new Dto.FilterOptions(
                s.statuses().stream().map(Refs::status).toList(),
                s.types().stream().filter(t -> p == null || p.typeIds().contains(t.id())).map(t -> Refs.type(s, t.id())).toList(),
                s.priorities().stream().map(x -> Refs.priority(s, x.id())).toList(),
                users,
                s.versions().stream().filter(v -> p == null || v.projectId().equals(p.id())).map(v -> new Dto.IdName(v.id(), v.name())).toList(),
                s.categories().stream().filter(c -> p == null || c.projectId().equals(p.id())).map(c -> new Dto.IdName(c.id(), c.name())).toList(),
                s.groups().values().stream().map(g -> new Dto.IdName(g.id(), g.name())).toList(),
                s.roles().stream().filter(r -> "project".equals(r.scope()) && r.builtin() == null).map(r -> new Dto.IdName(r.id(), r.name())).toList(),
                scope.stream().map(x -> new Dto.IdName(x.id(), x.name())).toList(),
                // custom fields on in any project of the scope: column and filter choices
                s.customFields().stream().filter(f -> scope.stream().anyMatch(x -> s.customFieldEnabledIn(f, x.id()))).map(customFields::dto).toList(),
                // S28: each type's form, resolved (the default where none is set)
                db.select(TYPES.ID, TYPES.ATTRIBUTE_GROUPS).from(TYPES).fetch().stream()
                        .collect(java.util.stream.Collectors.toMap(r -> r.value1().toString(), r -> com.openpms.wp.TypeForms.resolve(json, r.value2()))));
    }

    /* ---------- saved views ---------- */

    private static Map<String, Object> defaultSpec(List<Map<String, Object>> filters, boolean hierarchy, List<Map<String, Object>> sort, List<String> columns) {
        var m = new LinkedHashMap<String, Object>();
        m.put("v", 1);
        m.put("filters", filters);
        m.put("columns", columns);
        m.put("sort", sort);
        m.put("groupBy", null);
        m.put("hierarchy", hierarchy);
        m.put("sums", false);
        m.put("includeSubprojects", true);
        return m;
    }

    private static Map<String, Object> f(String field, String op, String... values) {
        var m = new LinkedHashMap<String, Object>();
        m.put("field", field);
        m.put("op", op);
        if (values.length > 0) m.put("values", List.of(values));
        return m;
    }

    private static Map<String, Object> by(String field, String dir) {
        return Map.of("field", field, "dir", dir);
    }

    /** Built-in views: code, not rows (architecture.md §2 설계 결정). */
    static List<Dto.SavedQuery> defaultViews(UUID projectId) {
        var number = List.of(by("number", "asc"));
        var recentColumns = new ArrayList<>(DEFAULT_COLUMNS.subList(0, DEFAULT_COLUMNS.size() - 1));
        recentColumns.add("updatedAt");
        record V(String id, String name, Map<String, Object> spec) {}
        return List.of(
                        new V("open", "모두 열림", defaultSpec(List.of(f("status", "open")), true, number, DEFAULT_COLUMNS)),
                        new V("assigned", "내게 할당됨", defaultSpec(List.of(f("assignee", "eq", "me"), f("status", "open")), false, number, DEFAULT_COLUMNS)),
                        new V("created", "내가 만듦", defaultSpec(List.of(f("author", "eq", "me")), false, number, DEFAULT_COLUMNS)),
                        new V("recent", "최근 업데이트", defaultSpec(List.of(), false, List.of(by("updatedAt", "desc")), recentColumns)),
                        new V("overdue", "기한 지남", defaultSpec(List.of(f("dueDate", "overdue")), false, List.of(by("dueDate", "asc")), DEFAULT_COLUMNS)),
                        new V("all", "모든 작업", defaultSpec(List.of(), true, number, DEFAULT_COLUMNS)))
                .stream().map(v -> new Dto.SavedQuery("default:" + v.id(), v.name(), "default", "table", v.spec(), false, projectId, false)).toList();
    }

    private Object spec(QueriesRecord q) {
        return json.readValue(q.getSpec().data(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private Dto.SavedQuery toDto(Ctx ctx, QueriesRecord q, Set<UUID> favorites) {
        var p = q.getProjectId() == null ? null : ctx.s().project(q.getProjectId()).orElse(null);
        boolean canEdit = q.getOwnerId().equals(ctx.userId()) || (p != null && q.getIsPublic() && ctx.can(p, "query.manage_public"));
        return new Dto.SavedQuery(q.getId().toString(), q.getName(), q.getIsPublic() ? "public" : "private", q.getView(), spec(q),
                favorites.contains(q.getId()), q.getProjectId(), canEdit);
    }

    private Set<UUID> favorites(Ctx ctx) {
        return Set.copyOf(db.select(QUERY_FAVORITES.QUERY_ID).from(QUERY_FAVORITES).where(QUERY_FAVORITES.USER_ID.eq(ctx.userId())).fetch(QUERY_FAVORITES.QUERY_ID));
    }

    @GetMapping("/queries")
    public List<Dto.SavedQuery> list(@RequestParam(required = false) String projectId, @RequestParam(required = false) String view) {
        var ctx = current.ctx();
        var p = projectId == null || projectId.isBlank() ? null : ctx.findProject(projectId);
        var cond = (p == null ? QUERIES.PROJECT_ID.isNull() : QUERIES.PROJECT_ID.eq(p.id()))
                .and(QUERIES.IS_PUBLIC.isTrue().or(QUERIES.OWNER_ID.eq(ctx.userId())))
                .and(view == null || view.isBlank() ? QUERIES.VIEW.in("table", "gantt") : QUERIES.VIEW.eq(view));
        var favs = favorites(ctx);
        var saved = db.selectFrom(QUERIES).where(cond).orderBy(QUERIES.CREATED_AT).fetch().stream().map(q -> toDto(ctx, q, favs)).toList();
        if (view != null && !view.isBlank()) return saved;
        var all = new ArrayList<>(defaultViews(p == null ? null : p.id()));
        all.addAll(saved);
        return all;
    }

    private QueriesRecord findQuery(Ctx ctx, UUID id) {
        var q = db.selectFrom(QUERIES).where(QUERIES.ID.eq(id)).fetchOne();
        if (q == null || !(q.getIsPublic() || q.getOwnerId().equals(ctx.userId()))) throw ApiException.notFound("보기를 찾을 수 없습니다.");
        if (q.getProjectId() != null && ctx.s().project(q.getProjectId()).map(p -> !ctx.can(p, "project.view")).orElse(true)) {
            throw ApiException.notFound("보기를 찾을 수 없습니다.");
        }
        return q;
    }

    private void requireOwnerOrManager(Ctx ctx, QueriesRecord q, String message) {
        var p = q.getProjectId() == null ? null : ctx.s().project(q.getProjectId()).orElse(null);
        if (!q.getOwnerId().equals(ctx.userId()) && !(p != null && ctx.can(p, "query.manage_public"))) throw ApiException.forbidden(message);
    }

    @PostMapping("/queries")
    @Transactional
    public ResponseEntity<Dto.SavedQuery> create(@RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var projectId = In.str(in, "projectId");
        Snapshot.ProjectRow p = projectId == null || projectId.isBlank() ? null : ctx.findProject(projectId);
        boolean isPublic = Boolean.TRUE.equals(In.bool(in, "isPublic"));
        if (p != null) ctx.require(p, isPublic ? "query.manage_public" : "query.save", isPublic ? "공개 보기를 저장할 권한이 없습니다." : "보기를 저장할 권한이 없습니다.");
        if (p == null && isPublic && !ctx.isAdmin()) throw ApiException.forbidden("공개 보기를 저장할 권한이 없습니다.");
        var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (name.isEmpty()) throw ApiException.field("name", "보기 이름을 입력하세요.");
        if (name.length() > 255) throw ApiException.field("name", "보기 이름은 255자 이하로 입력하세요.");
        var view = In.str(in, "view") == null ? "table" : In.str(in, "view");
        if (!VIEWS.contains(view)) throw ApiException.field("view", "알 수 없는 보기 종류입니다.");
        if ((view.equals("calendar") || view.equals("team_planner")) && p == null) throw ApiException.unprocessable("프로젝트가 필요합니다.", "캘린더와 팀 플래너는 프로젝트 안에서만 만들 수 있습니다.");
        parse(in.get("spec"));
        var q = db.newRecord(QUERIES);
        q.setProjectId(p == null ? null : p.id());
        q.setOwnerId(ctx.userId());
        q.setName(name);
        q.setView(view);
        q.setIsPublic(isPublic);
        q.setSpec(JSONB.valueOf(json.writeValueAsString(in.get("spec"))));
        q.store();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(ctx, q, Set.of()));
    }

    @PatchMapping("/queries/{id}")
    @Transactional
    public Dto.SavedQuery update(@PathVariable UUID id, @RequestBody Map<String, Object> patch) {
        var ctx = current.ctx();
        var q = findQuery(ctx, id);
        requireOwnerOrManager(ctx, q, "이 보기를 수정할 권한이 없습니다.");
        if (In.has(patch, "name")) {
            var name = In.str(patch, "name") == null ? "" : In.str(patch, "name").trim();
            if (name.isEmpty()) throw ApiException.field("name", "보기 이름을 입력하세요.");
            q.setName(name);
        }
        if (In.has(patch, "spec")) {
            parse(patch.get("spec"));
            q.setSpec(JSONB.valueOf(json.writeValueAsString(patch.get("spec"))));
        }
        if (In.has(patch, "isPublic")) {
            boolean pub = Boolean.TRUE.equals(In.bool(patch, "isPublic"));
            var p = q.getProjectId() == null ? null : ctx.s().project(q.getProjectId()).orElse(null);
            if (pub && p != null) ctx.require(p, "query.manage_public", "공개 보기를 저장할 권한이 없습니다.");
            q.setIsPublic(pub);
        }
        q.store();
        return toDto(ctx, q, favorites(ctx));
    }

    @DeleteMapping("/queries/{id}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        var ctx = current.ctx();
        var q = findQuery(ctx, id);
        requireOwnerOrManager(ctx, q, "이 보기를 삭제할 권한이 없습니다.");
        q.delete();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/queries/{id}/favorite")
    @Transactional
    public ResponseEntity<Void> favorite(@PathVariable UUID id) {
        var ctx = current.ctx();
        findQuery(ctx, id);
        db.insertInto(QUERY_FAVORITES).set(QUERY_FAVORITES.USER_ID, ctx.userId()).set(QUERY_FAVORITES.QUERY_ID, id).onConflictDoNothing().execute();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/queries/{id}/favorite")
    @Transactional
    public ResponseEntity<Void> unfavorite(@PathVariable UUID id) {
        var ctx = current.ctx();
        db.deleteFrom(QUERY_FAVORITES).where(QUERY_FAVORITES.USER_ID.eq(ctx.userId()), QUERY_FAVORITES.QUERY_ID.eq(id)).execute();
        return ResponseEntity.noContent().build();
    }
}

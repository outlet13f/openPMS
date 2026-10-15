package com.openpms.customfield;

import static com.openpms.db.Tables.CUSTOM_FIELDS;
import static com.openpms.db.Tables.CUSTOM_FIELD_OPTIONS;
import static com.openpms.db.Tables.CUSTOM_FIELD_TYPES;
import static com.openpms.db.Tables.PROJECT_CUSTOM_FIELDS;
import static com.openpms.db.Tables.QUERIES;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.jooq.impl.DSL.count;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Snapshot;
import com.openpms.core.Snapshot.CustomFieldRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * S34 관리: 사용자 정의 필드 (administrators) and S25 프로젝트 설정 (project.manage_custom_fields). The checks and
 * messages match frontend/src/api/fake/admin.ts buildField.
 */
@RestController
@RequestMapping("/api/v1")
public class CustomFieldController {
    private final Current current;
    private final DSLContext db;
    private final CustomFields customFields;
    private final JsonMapper json;

    public CustomFieldController(Current current, DSLContext db, CustomFields customFields, JsonMapper json) {
        this.current = current;
        this.db = db;
        this.customFields = customFields;
        this.json = json;
    }

    private Ctx admin() {
        var ctx = current.ctx();
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
        return ctx;
    }

    private List<Dto.AdminCustomField> list(Snapshot s) {
        var projects = db.select(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID, count()).from(PROJECT_CUSTOM_FIELDS).groupBy(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID)
                .fetchMap(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID, count());
        return s.customFields().stream().map(f -> {
            var d = customFields.dto(f);
            return new Dto.AdminCustomField(d.id(), d.name(), d.format(), d.isRequired(), d.isForAll(), d.isFilterable(), d.isMultiValue(), d.defaultValue(),
                    d.minLength(), d.maxLength(), d.regexp(), d.position(), d.options(), d.typeIds(), projects.getOrDefault(f.id(), 0));
        }).toList();
    }

    private Dto.AdminCustomField one(Ctx ctx, UUID id) {
        ctx.refresh();
        return list(ctx.s()).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @GetMapping("/admin/custom-fields")
    public List<Dto.AdminCustomField> list() {
        return list(admin().s());
    }

    /* ---------- definition checks ---------- */

    private record Option(UUID id, String value) {}

    private record Definition(String name, String format, boolean isRequired, boolean isForAll, boolean isFilterable, boolean isMultiValue,
                              Object defaultValue, Integer minLength, Integer maxLength, String regexp, List<Option> options, List<UUID> typeIds) {}

    private static boolean bool(Map<String, Object> in, String key, boolean fallback) {
        return In.has(in, key) ? Boolean.TRUE.equals(In.bool(in, key)) : fallback;
    }

    /** Request body (merged onto the stored field for a PATCH) → a checked definition. New options get ids. */
    private Definition definition(Snapshot s, Map<String, Object> in, CustomFieldRow existing) {
        var name = In.has(in, "name") ? In.str(in, "name") : existing == null ? null : existing.name();
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw ApiException.field("name", "이름을 입력하세요.");
        if (name.length() > 100) throw ApiException.field("name", "이름은 100자 이하로 입력하세요.");
        var n = name;
        if (s.customFields().stream().anyMatch(f -> (existing == null || !f.id().equals(existing.id())) && f.name().trim().equals(n)))
            throw ApiException.field("name", "같은 이름의 필드가 이미 있습니다.");
        var format = In.has(in, "format") ? In.str(in, "format") : existing == null ? null : existing.format();
        if (format == null || !CustomFields.FORMATS.contains(format)) throw ApiException.field("format", "형식을 고르세요.");
        if (existing != null && !existing.format().equals(format)) throw ApiException.field("format", "만든 뒤에는 형식을 바꿀 수 없습니다.");
        boolean refs = format.equals("list") || format.equals("user");
        boolean multi = bool(in, "isMultiValue", existing != null && existing.isMultiValue());
        if (multi && !refs) throw ApiException.field("isMultiValue", "여러 값은 목록과 사용자 형식에서만 고를 수 있습니다.");
        var typeIds = In.has(in, "typeIds") ? In.uuids(in, "typeIds", "typeIds") : existing == null ? List.<UUID>of() : existing.typeIds();
        if (typeIds.isEmpty()) throw ApiException.field("typeIds", "이 필드를 쓸 유형을 하나 이상 고르세요.");
        if (typeIds.stream().anyMatch(id -> s.type(id).isEmpty())) throw ApiException.field("typeIds", "없는 유형입니다.");
        typeIds = s.types().stream().map(Snapshot.TypeRow::id).filter(typeIds::contains).toList();
        boolean lengths = format.equals("string") || format.equals("text");
        Integer min = lengths ? (In.has(in, "minLength") ? In.integer(in, "minLength", "minLength") : existing == null ? null : existing.minLength()) : null;
        Integer max = lengths ? (In.has(in, "maxLength") ? In.integer(in, "maxLength", "maxLength") : existing == null ? null : existing.maxLength()) : null;
        if (min != null && min < 0) throw ApiException.field("minLength", "최소 길이는 0 이상의 정수로 입력하세요.");
        if (max != null && max < 1) throw ApiException.field("maxLength", "최대 길이는 1 이상의 정수로 입력하세요.");
        if (min != null && max != null && max < min) throw ApiException.field("maxLength", "최대 길이는 최소 길이보다 작을 수 없습니다.");
        var regexp = lengths ? (In.has(in, "regexp") ? In.str(in, "regexp") : existing == null ? null : existing.regexp()) : null;
        regexp = regexp == null || regexp.isBlank() ? null : regexp.trim();
        if (regexp != null) {
            try {
                Pattern.compile(regexp);
            } catch (PatternSyntaxException e) {
                throw ApiException.field("regexp", "정규식이 올바르지 않습니다.");
            }
        }
        var options = new ArrayList<Option>();
        if (format.equals("list")) {
            List<Option> given;
            if (In.has(in, "options")) {
                if (!(in.get("options") instanceof Collection<?> c)) throw ApiException.field("options", "선택지를 하나 이상 입력하세요(빈 칸 없이).");
                given = new ArrayList<>();
                for (var o : c) {
                    if (!(o instanceof Map<?, ?> m)) throw ApiException.field("options", "선택지를 하나 이상 입력하세요(빈 칸 없이).");
                    var v = m.get("value") == null ? "" : String.valueOf(m.get("value")).trim();
                    UUID id = null;
                    try {
                        id = m.get("id") == null ? null : UUID.fromString(String.valueOf(m.get("id")));
                    } catch (IllegalArgumentException ignored) {
                        // a client-side placeholder id: a new option
                    }
                    given.add(new Option(id, v));
                }
            } else {
                given = existing == null ? List.of() : existing.options().stream().map(o -> new Option(o.id(), o.value())).toList();
            }
            if (given.isEmpty() || given.stream().anyMatch(o -> o.value().isEmpty())) throw ApiException.field("options", "선택지를 하나 이상 입력하세요(빈 칸 없이).");
            if (given.stream().anyMatch(o -> o.value().length() > 255)) throw ApiException.field("options", "선택지는 255자까지 입력할 수 있습니다.");
            if (new HashSet<>(given.stream().map(Option::value).toList()).size() != given.size()) throw ApiException.field("options", "같은 선택지가 두 번 있습니다.");
            for (var o : given) {
                boolean known = o.id() != null && existing != null && existing.options().stream().anyMatch(x -> x.id().equals(o.id()));
                options.add(new Option(known ? o.id() : UUID.randomUUID(), o.value()));
            }
        }
        // no default for people: who is a member differs per project; a list default names an option by id or text
        Object def = format.equals("user") ? null : In.has(in, "defaultValue") ? in.get("defaultValue") : existing == null ? null : existing.defaultValue();
        if (format.equals("list") && def != null) {
            java.util.function.Function<Object, Object> pick = x -> options.stream().filter(o -> o.id().toString().equals(String.valueOf(x)) || o.value().equals(String.valueOf(x)))
                    .map(o -> (Object) o.id().toString()).findFirst().orElse(x);
            def = def instanceof Collection<?> c ? c.stream().map(pick).toList() : pick.apply(def);
        }
        var draft = new CustomFieldRow(existing == null ? UUID.randomUUID() : existing.id(), name, format, bool(in, "isRequired", existing != null && existing.isRequired()),
                bool(in, "isForAll", existing != null && existing.isForAll()), bool(in, "isFilterable", existing == null || existing.isFilterable()), multi, null,
                min, max, regexp, 0, options.stream().map(o -> new Snapshot.OptionRow(o.id(), o.value(), 0)).toList(), typeIds);
        Object checked;
        try {
            checked = customFields.check(s, draft, def, null);
        } catch (ApiException e) {
            throw ApiException.field("defaultValue", "기본값이 이 필드의 형식·조건에 맞지 않습니다.");
        }
        return new Definition(name, format, draft.isRequired(), draft.isForAll(), draft.isFilterable(), multi, checked, min, max, regexp, options, typeIds);
    }

    private void store(UUID id, Definition d) {
        db.update(CUSTOM_FIELDS)
                .set(CUSTOM_FIELDS.NAME, d.name()).set(CUSTOM_FIELDS.IS_REQUIRED, d.isRequired()).set(CUSTOM_FIELDS.IS_FOR_ALL, d.isForAll())
                .set(CUSTOM_FIELDS.IS_FILTERABLE, d.isFilterable()).set(CUSTOM_FIELDS.IS_MULTI_VALUE, d.isMultiValue())
                .set(CUSTOM_FIELDS.DEFAULT_VALUE, d.defaultValue() == null ? null : JSONB.valueOf(json.writeValueAsString(d.defaultValue())))
                .set(CUSTOM_FIELDS.MIN_LENGTH, d.minLength()).set(CUSTOM_FIELDS.MAX_LENGTH, d.maxLength()).set(CUSTOM_FIELDS.REGEXP, d.regexp())
                .where(CUSTOM_FIELDS.ID.eq(id)).execute();
        var keep = d.options().stream().map(Option::id).toList();
        db.deleteFrom(CUSTOM_FIELD_OPTIONS).where(CUSTOM_FIELD_OPTIONS.CUSTOM_FIELD_ID.eq(id), keep.isEmpty() ? DSL.trueCondition() : CUSTOM_FIELD_OPTIONS.ID.notIn(keep)).execute();
        // (field, value) is unique: park every kept or new option on a temporary value, then write the real ones
        for (int i = 0; i < d.options().size(); i++) {
            var o = d.options().get(i);
            var parked = "~reorder~" + o.id();
            int updated = db.update(CUSTOM_FIELD_OPTIONS).set(CUSTOM_FIELD_OPTIONS.VALUE, parked).where(CUSTOM_FIELD_OPTIONS.ID.eq(o.id())).execute();
            if (updated == 0)
                db.insertInto(CUSTOM_FIELD_OPTIONS).set(CUSTOM_FIELD_OPTIONS.ID, o.id()).set(CUSTOM_FIELD_OPTIONS.CUSTOM_FIELD_ID, id)
                        .set(CUSTOM_FIELD_OPTIONS.VALUE, parked).set(CUSTOM_FIELD_OPTIONS.POSITION, i + 1).execute();
        }
        for (int i = 0; i < d.options().size(); i++) {
            var o = d.options().get(i);
            db.update(CUSTOM_FIELD_OPTIONS).set(CUSTOM_FIELD_OPTIONS.VALUE, o.value()).set(CUSTOM_FIELD_OPTIONS.POSITION, i + 1).where(CUSTOM_FIELD_OPTIONS.ID.eq(o.id())).execute();
        }
        db.deleteFrom(CUSTOM_FIELD_TYPES).where(CUSTOM_FIELD_TYPES.CUSTOM_FIELD_ID.eq(id)).execute();
        for (var t : d.typeIds()) db.insertInto(CUSTOM_FIELD_TYPES).set(CUSTOM_FIELD_TYPES.CUSTOM_FIELD_ID, id).set(CUSTOM_FIELD_TYPES.TYPE_ID, t).execute();
        if (d.isForAll()) db.deleteFrom(PROJECT_CUSTOM_FIELDS).where(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID.eq(id)).execute();
    }

    @PostMapping("/admin/custom-fields")
    @Transactional
    public ResponseEntity<Dto.AdminCustomField> create(@RequestBody Map<String, Object> body) {
        var ctx = admin();
        var d = definition(ctx.s(), body, null);
        var position = db.select(DSL.coalesce(DSL.max(CUSTOM_FIELDS.POSITION), 0)).from(CUSTOM_FIELDS).where(CUSTOM_FIELDS.ENTITY.eq("work_package")).fetchOne(0, Integer.class);
        var id = db.insertInto(CUSTOM_FIELDS).set(CUSTOM_FIELDS.ENTITY, "work_package").set(CUSTOM_FIELDS.NAME, d.name()).set(CUSTOM_FIELDS.FORMAT, d.format())
                .set(CUSTOM_FIELDS.POSITION, position + 1).returning(CUSTOM_FIELDS.ID).fetchOne().getId();
        store(id, d);
        return ResponseEntity.status(HttpStatus.CREATED).body(one(ctx, id));
    }

    private CustomFieldRow find(Snapshot s, UUID id) {
        return s.customField(id).orElseThrow(() -> ApiException.notFound("사용자 정의 필드을(를) 찾을 수 없습니다."));
    }

    @PatchMapping("/admin/custom-fields/{id}")
    @Transactional
    public Dto.AdminCustomField update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        var ctx = admin();
        var existing = find(ctx.s(), id);
        var d = definition(ctx.s(), body, existing);
        store(id, d);
        migrateValues(existing, d);
        return one(ctx, id);
    }

    /** Stored values follow a definition change: removed options go, single and multi convert. Item times stay. */
    private void migrateValues(CustomFieldRow before, Definition d) {
        boolean list = d.format().equals("list");
        boolean user = d.format().equals("user");
        if (!list && !user) return;
        var optionIds = d.options().stream().map(o -> o.id().toString()).toList();
        boolean optionsGone = list && before.options().stream().anyMatch(o -> !optionIds.contains(o.id().toString()));
        if (!optionsGone && before.isMultiValue() == d.isMultiValue()) return;
        keepUpdatedAt();
        var key = before.id().toString();
        var rows = db.select(WORK_PACKAGES.ID, WORK_PACKAGES.CUSTOM_VALUES).from(WORK_PACKAGES)
                .where(DSL.condition("jsonb_exists({0}, {1})", WORK_PACKAGES.CUSTOM_VALUES, DSL.val(key))).fetch();
        for (var r : rows) {
            var values = customFields.read(r.value2());
            var v = values.get(key);
            List<String> ids = v instanceof Collection<?> c ? c.stream().map(String::valueOf).toList() : v == null ? List.of() : List.of(String.valueOf(v));
            if (list) ids = ids.stream().filter(optionIds::contains).toList();
            Object next = ids.isEmpty() ? null : d.isMultiValue() ? ids : ids.get(0);
            if (Objects.equals(next, v)) continue;
            if (next == null) values.remove(key);
            else values.put(key, next);
            db.update(WORK_PACKAGES).set(WORK_PACKAGES.CUSTOM_VALUES, customFields.write(values)).where(WORK_PACKAGES.ID.eq(r.value1())).execute();
        }
    }

    private void keepUpdatedAt() {
        db.execute("set local openpms.keep_updated_at = 'on'");
    }

    @DeleteMapping("/admin/custom-fields/{id}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        var ctx = admin();
        find(ctx.s(), id);
        keepUpdatedAt();
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.CUSTOM_VALUES, DSL.field("{0} - {1}", WORK_PACKAGES.CUSTOM_VALUES.getDataType(), WORK_PACKAGES.CUSTOM_VALUES, DSL.val(id.toString())))
                .where(DSL.condition("jsonb_exists({0}, {1})", WORK_PACKAGES.CUSTOM_VALUES, DSL.val(id.toString()))).execute();
        forgetInQueries("cf:" + id);
        db.deleteFrom(CUSTOM_FIELDS).where(CUSTOM_FIELDS.ID.eq(id)).execute();
        var ids = db.select(CUSTOM_FIELDS.ID).from(CUSTOM_FIELDS).where(CUSTOM_FIELDS.ENTITY.eq("work_package")).orderBy(CUSTOM_FIELDS.POSITION).fetch(CUSTOM_FIELDS.ID);
        for (int i = 0; i < ids.size(); i++) db.update(CUSTOM_FIELDS).set(CUSTOM_FIELDS.POSITION, i + 1).where(CUSTOM_FIELDS.ID.eq(ids.get(i))).execute();
        return ResponseEntity.noContent().build();
    }

    /** Saved views forget a deleted field: its column, sort and filter. */
    @SuppressWarnings("unchecked")
    private void forgetInQueries(String key) {
        var rows = db.select(QUERIES.ID, QUERIES.SPEC).from(QUERIES).where(QUERIES.SPEC.cast(String.class).contains(key)).fetch();
        for (var r : rows) {
            var spec = new LinkedHashMap<String, Object>((Map<String, Object>) json.readValue(r.value2().data(), Map.class));
            if (spec.get("columns") instanceof List<?> cols) spec.put("columns", cols.stream().filter(c -> !key.equals(c)).toList());
            if (spec.get("sort") instanceof List<?> sort) spec.put("sort", sort.stream().filter(x -> !(x instanceof Map<?, ?> m && key.equals(m.get("field")))).toList());
            if (spec.get("filters") instanceof List<?> fs) spec.put("filters", fs.stream().filter(x -> !(x instanceof Map<?, ?> m && key.equals(m.get("field")))).toList());
            db.update(QUERIES).set(QUERIES.SPEC, JSONB.valueOf(json.writeValueAsString(spec))).where(QUERIES.ID.eq(r.value1())).execute();
        }
    }

    @PutMapping("/admin/custom-fields/order")
    @Transactional
    public ResponseEntity<Void> reorder(@RequestBody Map<String, Object> body) {
        admin();
        var ids = In.uuids(body, "ids", "ids");
        var existing = new HashSet<>(db.select(CUSTOM_FIELDS.ID).from(CUSTOM_FIELDS).where(CUSTOM_FIELDS.ENTITY.eq("work_package")).fetch(CUSTOM_FIELDS.ID));
        if (ids.size() != existing.size() || !existing.containsAll(ids)) throw ApiException.unprocessable("순서 목록이 맞지 않습니다.", null);
        for (int i = 0; i < ids.size(); i++) db.update(CUSTOM_FIELDS).set(CUSTOM_FIELDS.POSITION, i + 1).where(CUSTOM_FIELDS.ID.eq(ids.get(i))).execute();
        return ResponseEntity.noContent().build();
    }

    /* ---------- project settings (S25) ---------- */

    private Dto.ProjectCustomFields projectFields(Snapshot s, UUID projectId) {
        return new Dto.ProjectCustomFields(s.customFields().stream().map(customFields::dto).toList(), List.copyOf(s.projectCustomFieldIds(projectId)));
    }

    @GetMapping("/projects/{project}/custom-fields")
    public Dto.ProjectCustomFields projectFields(@PathVariable String project) {
        var ctx = current.ctx();
        var p = ctx.findProject(project);
        ctx.require(p, "project.manage_custom_fields", "사용자 정의 필드를 관리할 권한이 없습니다.");
        return projectFields(ctx.s(), p.id());
    }

    @PutMapping("/projects/{project}/custom-fields")
    @Transactional
    public Dto.ProjectCustomFields setProjectFields(@PathVariable String project, @RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var p = ctx.findProject(project);
        ctx.require(p, "project.manage_custom_fields", "사용자 정의 필드를 관리할 권한이 없습니다.");
        var ids = In.uuids(body, "customFieldIds", "customFieldIds");
        var s = ctx.s();
        if (ids.stream().anyMatch(id -> s.customField(id).isEmpty())) throw ApiException.field("customFieldIds", "없는 사용자 정의 필드입니다.");
        db.deleteFrom(PROJECT_CUSTOM_FIELDS).where(PROJECT_CUSTOM_FIELDS.PROJECT_ID.eq(p.id())).execute();
        for (var f : s.customFields()) {
            if (f.isForAll() || !ids.contains(f.id())) continue;
            db.insertInto(PROJECT_CUSTOM_FIELDS).set(PROJECT_CUSTOM_FIELDS.PROJECT_ID, p.id()).set(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID, f.id()).execute();
        }
        ctx.refresh();
        return projectFields(ctx.s(), p.id());
    }
}

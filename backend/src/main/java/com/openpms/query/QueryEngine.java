package com.openpms.query;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.falseCondition;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.or;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.trueCondition;

import com.openpms.api.Dto;
import com.openpms.customfield.CustomFields;
import com.openpms.common.ApiException;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot;
import com.openpms.core.Text;
import com.openpms.db.tables.WorkPackages;
import com.openpms.wp.Assembler;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * The list engine (architecture.md §3.5): filters become SQL conditions; sorting and the hierarchy tree follow
 * the same comparison rules as the fake data layer (Korean collation for names), then only the page is turned
 * into DTOs.
 */
@Component
public class QueryEngine {
    public record Filter(String field, String op, List<String> values) {}

    public record Sort(String field, String dir) {}

    public record Spec(List<Filter> filters, List<Sort> sort, boolean hierarchy, boolean includeSubprojects, String text, boolean timeline) {}

    private static final Map<String, Set<String>> OPS = new HashMap<>();

    static {
        var ref = Set.of("eq", "neq", "any", "none");
        var date = Set.of("overdue", "today", "this_week", "next_days", "last_days", "on", "between", "any", "none");
        var num = Set.of("eq", "gte", "lte", "any", "none");
        var text = Set.of("contains", "not_contains");
        OPS.put("status", Set.of("open", "closed", "eq", "neq"));
        for (var f : List.of("type", "priority", "assignee", "responsible", "author", "version", "category", "project")) OPS.put(f, ref);
        for (var f : List.of("watcher", "assigneeGroup", "assigneeRole", "number")) OPS.put(f, Set.of("eq", "neq"));
        OPS.put("parent", Set.of("eq", "any", "none"));
        for (var f : List.of("subject", "description", "comment")) OPS.put(f, text);
        for (var f : List.of("startDate", "dueDate", "createdAt", "updatedAt")) OPS.put(f, date);
        OPS.put("dates", Set.of("overlaps", "between", "any", "none"));
        for (var f : List.of("duration", "doneRatio", "work", "remainingWork")) OPS.put(f, num);
        for (var f : List.of("isMilestone", "scheduleManually", "blocked")) OPS.put(f, Set.of("eq"));
    }

    private static final Set<String> NEEDS_VALUE = Set.of("eq", "neq", "gte", "lte", "on", "between", "overlaps", "contains", "not_contains");

    private final DSLContext db;
    private final Assembler assembler;
    private final CustomFields customFields;

    public QueryEngine(DSLContext db, Assembler assembler, CustomFields customFields) {
        this.db = db;
        this.assembler = assembler;
        this.customFields = customFields;
    }

    /* ---------- filters → SQL ---------- */

    private static final WorkPackages W = WORK_PACKAGES;

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private Condition ref(Field<UUID> col, String op, List<String> vals) {
        var ids = vals.stream().map(QueryEngine::uuid).filter(x -> x != null).toList();
        boolean none = vals.contains("none");
        return switch (op) {
            case "eq" -> (ids.isEmpty() ? falseCondition() : col.in(ids)).or(none ? col.isNull() : falseCondition());
            case "neq" -> (ids.isEmpty() ? trueCondition() : col.notIn(ids).or(col.isNull())).and(none ? col.isNotNull() : trueCondition());
            case "any" -> col.isNotNull();
            default -> col.isNull();
        };
    }

    private Condition date(Field<LocalDate> col, String op, List<String> vals, LocalDate today, Set<UUID> closed) {
        int n = 7;
        try {
            if (!vals.isEmpty()) n = Math.max(1, Integer.parseInt(vals.get(0)));
        } catch (NumberFormatException ignored) {
            // default 7 days
        }
        return switch (op) {
            case "any" -> col.isNotNull();
            case "none" -> col.isNull();
            case "overdue" -> col.lt(today).and(closed.isEmpty() ? trueCondition() : W.STATUS_ID.notIn(closed));
            case "today" -> col.eq(today);
            case "this_week" -> col.between(today.minusDays(today.getDayOfWeek().getValue() - 1), today.minusDays(today.getDayOfWeek().getValue() - 1).plusDays(6));
            case "next_days" -> col.between(today, today.plusDays(n));
            case "last_days" -> col.between(today.minusDays(n), today);
            case "on" -> parseDate(vals.get(0)) == null ? noCondition() : col.eq(parseDate(vals.get(0)));
            default -> { // between, open ends allowed
                var from = vals.size() > 0 ? parseDate(vals.get(0)) : null;
                var to = vals.size() > 1 ? parseDate(vals.get(1)) : null;
                var c = col.isNotNull();
                if (from != null) c = c.and(col.ge(from));
                if (to != null) c = c.and(col.le(to));
                yield c;
            }
        };
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <N extends Number> Condition num(Field<N> col, String op, List<String> vals) {
        Double v = null;
        try {
            v = vals.isEmpty() ? null : Double.valueOf(vals.get(0));
        } catch (NumberFormatException ignored) {
            // not narrowed
        }
        var f = col.cast(Double.class);
        return switch (op) {
            case "any" -> col.isNotNull();
            case "none" -> col.isNull();
            case "eq" -> v == null ? noCondition() : f.eq(v);
            case "gte" -> v == null ? noCondition() : f.ge(v);
            default -> v == null ? noCondition() : f.le(v);
        };
    }

    private static Condition text(Field<String> col, String op, String needle) {
        return "not_contains".equals(op) ? col.containsIgnoreCase(needle).not() : col.containsIgnoreCase(needle);
    }

    /**
     * A custom field filter over work_packages.custom_values. An item without the field (other type, or the field is
     * off in its project) counts as empty, whatever is stored for it.
     */
    private Condition custom(Ctx ctx, Snapshot.CustomFieldRow cf, String op, List<String> vals, Set<UUID> closed) {
        var s = ctx.s();
        var id = cf.id().toString();
        var on = s.projects().values().stream().filter(p -> s.customFieldEnabledIn(cf, p.id())).map(Snapshot.ProjectRow::id).toList();
        Condition active = (cf.typeIds().isEmpty() ? falseCondition() : W.TYPE_ID.in(cf.typeIds()))
                .and(cf.isForAll() ? trueCondition() : on.isEmpty() ? falseCondition() : W.PROJECT_ID.in(on));
        Condition has = active.and(DSL.condition("jsonb_exists({0}, {1})", W.CUSTOM_VALUES, DSL.val(id)));
        if ("any".equals(op)) return has;
        if ("none".equals(op)) return has.not();
        Field<JSONB> raw = DSL.field("({0} -> {1})", SQLDataType.JSONB, W.CUSTOM_VALUES, DSL.val(id));
        String needle = vals.isEmpty() || vals.get(0) == null ? "" : vals.get(0);
        return switch (cf.format()) {
            case "list", "user" -> {
                var ids = vals.stream().filter(v -> v != null && !v.isEmpty()).toArray(String[]::new);
                // jsonb_exists_any matches a string value or any element of an array of them
                Condition hit = ids.length == 0 ? falseCondition() : has.and(DSL.condition("jsonb_exists_any({0}, {1}::text[])", raw, DSL.val(ids)));
                yield "neq".equals(op) ? hit.not() : hit;
            }
            case "integer", "float" -> has.and(num(DSL.field("({0} ->> {1})::numeric", SQLDataType.NUMERIC, W.CUSTOM_VALUES, DSL.val(id)), op, vals));
            case "date" -> has.and(date(DSL.field("({0} ->> {1})::date", SQLDataType.LOCALDATE, W.CUSTOM_VALUES, DSL.val(id)), op, vals, ctx.today(), closed));
            case "bool" -> has.and(DSL.condition("({0} -> {1}) = {2}::jsonb", W.CUSTOM_VALUES, DSL.val(id), DSL.val("true".equals(needle) ? "true" : "false")));
            default -> {
                Condition hit = has.and(DSL.field("({0} ->> {1})", SQLDataType.VARCHAR, W.CUSTOM_VALUES, DSL.val(id)).containsIgnoreCase(needle));
                yield "not_contains".equals(op) ? hit.not() : hit;
            }
        };
    }

    Condition condition(Ctx ctx, Filter f) {
        var s = ctx.s();
        if (f.field() != null && f.field().startsWith("cf:")) {
            var cfId = CustomFields.idOf(f.field());
            var cf = cfId == null ? null : s.customField(cfId).orElse(null);
            if (cf == null) throw ApiException.unprocessable("필터를 확인하세요.", "없는 사용자 정의 필드입니다.");
            if (!CustomFields.ops(cf).contains(f.op())) throw ApiException.unprocessable("필터를 확인하세요.", "연산자 " + f.op() + "는 " + cf.name() + " 필드에 쓸 수 없습니다.");
            var vals = (f.values() == null ? List.<String>of() : f.values()).stream().map(v -> "me".equals(v) ? ctx.userId().toString() : v).toList();
            if (NEEDS_VALUE.contains(f.op()) && vals.stream().allMatch(v -> v == null || v.isEmpty())) return noCondition();
            var closed = new HashSet<UUID>();
            s.statuses().stream().filter(Snapshot.StatusRow::isClosed).forEach(x -> closed.add(x.id()));
            return custom(ctx, cf, f.op(), vals, closed);
        }
        var allowed = OPS.get(f.field());
        if (allowed == null || !allowed.contains(f.op())) throw ApiException.unprocessable("필터를 확인하세요.", "연산자 " + f.op() + "는 " + f.field() + " 필드에 쓸 수 없습니다.");
        var vals = (f.values() == null ? List.<String>of() : f.values()).stream().map(v -> "me".equals(v) ? ctx.userId().toString() : v).toList();
        if (NEEDS_VALUE.contains(f.op()) && vals.stream().allMatch(v -> v == null || v.isEmpty())) return noCondition();
        var today = ctx.today();
        var closed = new HashSet<UUID>();
        s.statuses().stream().filter(Snapshot.StatusRow::isClosed).forEach(x -> closed.add(x.id()));
        String needle = vals.isEmpty() || vals.get(0) == null ? "" : vals.get(0);
        boolean yes = "true".equals(needle);

        return switch (f.field()) {
            case "status" -> switch (f.op()) {
                case "open" -> closed.isEmpty() ? trueCondition() : W.STATUS_ID.notIn(closed);
                case "closed" -> closed.isEmpty() ? falseCondition() : W.STATUS_ID.in(closed);
                default -> ref(W.STATUS_ID, f.op(), vals);
            };
            case "type" -> ref(W.TYPE_ID, f.op(), vals);
            case "priority" -> ref(W.PRIORITY_ID, f.op(), vals);
            case "assignee" -> ref(W.ASSIGNEE_ID, f.op(), vals);
            case "responsible" -> ref(W.RESPONSIBLE_ID, f.op(), vals);
            case "author" -> ref(W.AUTHOR_ID, f.op(), vals);
            case "version" -> ref(W.VERSION_ID, f.op(), vals);
            case "category" -> ref(W.CATEGORY_ID, f.op(), vals);
            case "project" -> ref(W.PROJECT_ID, f.op(), vals);
            case "watcher" -> {
                var ids = vals.stream().map(QueryEngine::uuid).filter(x -> x != null).toList();
                var watched = selectOne().from(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.eq(W.ID), WATCHERS.USER_ID.in(ids));
                yield "eq".equals(f.op()) ? exists(watched) : notExists(watched);
            }
            case "assigneeGroup" -> {
                var people = new HashSet<UUID>();
                vals.stream().map(QueryEngine::uuid).filter(x -> x != null && s.groups().containsKey(x)).forEach(g -> people.addAll(s.groups().get(g).memberIds()));
                var in = people.isEmpty() ? falseCondition() : W.ASSIGNEE_ID.in(people);
                yield "eq".equals(f.op()) ? in : in.not().or(W.ASSIGNEE_ID.isNull());
            }
            case "assigneeRole" -> {
                var roles = vals.stream().map(QueryEngine::uuid).filter(x -> x != null).toList();
                var parts = new ArrayList<Condition>();
                for (var p : s.projects().values()) {
                    var people = s.projectUsers(p.id()).stream().map(Snapshot.UserRow::id)
                            .filter(u -> s.memberRoleIds(u, p.id()).stream().anyMatch(roles::contains)).toList();
                    if (!people.isEmpty()) parts.add(W.PROJECT_ID.eq(p.id()).and(W.ASSIGNEE_ID.in(people)));
                }
                var in = parts.isEmpty() ? falseCondition() : or(parts);
                yield "eq".equals(f.op()) ? in : in.not().or(W.ASSIGNEE_ID.isNull());
            }
            case "number" -> {
                var nums = new ArrayList<Long>();
                for (var v : vals) for (var part : v.split("[\\s,]+")) {
                    try {
                        var x = Long.parseLong(part.replace("#", ""));
                        if (x > 0) nums.add(x);
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
                yield "eq".equals(f.op()) ? (nums.isEmpty() ? falseCondition() : W.NUMBER.in(nums)) : (nums.isEmpty() ? trueCondition() : W.NUMBER.notIn(nums));
            }
            case "parent" -> switch (f.op()) {
                case "any" -> W.PARENT_ID.isNotNull();
                case "none" -> W.PARENT_ID.isNull();
                default -> {
                    long n;
                    try {
                        n = Long.parseLong(needle.replace("#", "").trim());
                    } catch (NumberFormatException e) {
                        yield falseCondition();
                    }
                    var p = WORK_PACKAGES.as("p");
                    yield W.PARENT_ID.in(db.select(p.ID).from(p).where(p.NUMBER.eq(n)));
                }
            };
            case "subject" -> text(W.SUBJECT, f.op(), needle);
            case "description" -> text(W.DESCRIPTION_TEXT, f.op(), needle);
            case "comment" -> {
                var hit = selectOne().from(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(W.ID), JOURNALS.NOTES_TEXT.containsIgnoreCase(needle));
                yield "not_contains".equals(f.op()) ? notExists(hit) : exists(hit);
            }
            case "isMilestone" -> {
                var ms = s.types().stream().filter(Snapshot.TypeRow::isMilestone).map(Snapshot.TypeRow::id).toList();
                var in = ms.isEmpty() ? falseCondition() : W.TYPE_ID.in(ms);
                yield yes ? in : in.not();
            }
            case "scheduleManually" -> W.SCHEDULE_MANUALLY.eq(yes);
            case "blocked" -> {
                var b = WORK_PACKAGES.as("b");
                var blocker = selectOne().from(RELATIONS).join(b).on(b.ID.eq(RELATIONS.FROM_ID))
                        .where(RELATIONS.TYPE.eq("blocks"), RELATIONS.TO_ID.eq(W.ID), closed.isEmpty() ? trueCondition() : b.STATUS_ID.notIn(closed));
                yield yes ? exists(blocker) : notExists(blocker);
            }
            case "dueDate" -> date(W.DUE_DATE, f.op(), vals, today, closed);
            case "startDate" -> date(W.START_DATE, f.op(), vals, today, closed);
            case "createdAt" -> date(W.CREATED_AT.cast(LocalDate.class), f.op(), vals, today, closed);
            case "updatedAt" -> date(W.UPDATED_AT.cast(LocalDate.class), f.op(), vals, today, closed);
            case "duration" -> num(W.DURATION, f.op(), vals);
            case "doneRatio" -> num(W.DONE_RATIO, f.op(), vals);
            case "work" -> num(W.WORK_HOURS, f.op(), vals);
            case "remainingWork" -> num(W.REMAINING_HOURS, f.op(), vals);
            case "dates" -> {
                var start = coalesce(W.START_DATE, W.DUE_DATE);
                var end = coalesce(W.DUE_DATE, W.START_DATE);
                if ("any".equals(f.op())) yield start.isNotNull();
                if ("none".equals(f.op())) yield start.isNull();
                if ("overlaps".equals(f.op()) && vals.size() < 2) throw ApiException.unprocessable("필터를 확인하세요.", "날짜 범위 필터는 시작일과 종료일이 필요합니다.");
                var from = vals.size() > 0 ? parseDate(vals.get(0)) : null;
                var to = vals.size() > 1 ? parseDate(vals.get(1)) : null;
                var c = start.isNotNull();
                if (to != null) c = c.and(start.le(to));
                if (from != null) c = c.and(end.ge(from));
                yield c;
            }
            default -> noCondition();
        };
    }

    /* ---------- sort ---------- */

    record Light(UUID id, long number, UUID parentId, UUID projectId, String subject, UUID typeId, UUID statusId, UUID priorityId, UUID assigneeId,
                 UUID responsibleId, UUID authorId, LocalDate startDate, LocalDate dueDate, OffsetDateTime updatedAt, JSONB customValues) {}

    /** Sort key of a custom field: empty (or not on for the item) sorts last. Parsed values are cached per run. */
    private Comparable<?> customSortValue(Snapshot s, Light w, String field, Map<UUID, Map<String, Object>> parsed) {
        var cfId = CustomFields.idOf(field);
        var cf = cfId == null ? null : s.customField(cfId).orElse(null);
        if (cf == null) return w.number();
        boolean active = cf.typeIds().contains(w.typeId()) && s.customFieldEnabledIn(cf, w.projectId());
        var v = active ? parsed.computeIfAbsent(w.id(), k -> customFields.read(w.customValues())).get(cf.id().toString()) : null;
        return customFields.sortKey(s, cf, v);
    }

    private static Comparable<?> sortValue(Snapshot s, Light w, String field) {
        java.util.function.Function<UUID, String> name = id -> id == null ? "￿" : s.user(id).map(Snapshot.UserRow::name).orElse("");
        return switch (field) {
            case "number" -> w.number();
            case "subject" -> w.subject();
            case "type" -> s.type(w.typeId()).map(Snapshot.TypeRow::position).orElse(0);
            case "status" -> s.status(w.statusId()).map(Snapshot.StatusRow::position).orElse(0);
            case "priority" -> s.priority(w.priorityId()).map(Snapshot.PriorityRow::position).orElse(0);
            case "assignee" -> name.apply(w.assigneeId());
            case "responsible" -> name.apply(w.responsibleId());
            case "author" -> name.apply(w.authorId());
            case "startDate" -> w.startDate() == null ? "9999" : w.startDate().toString();
            case "dueDate" -> w.dueDate() == null ? "9999" : w.dueDate().toString();
            case "updatedAt" -> w.updatedAt().toInstant().toString();
            case "project" -> s.project(w.projectId()).map(Snapshot.ProjectRow::name).orElse("");
            default -> w.number();
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<Light> comparator(Snapshot s, List<Sort> sort) {
        var keys = sort == null || sort.isEmpty() ? List.of(new Sort("number", "asc")) : sort;
        var parsed = new HashMap<UUID, Map<String, Object>>();
        return (a, b) -> {
            for (var k : keys) {
                boolean cf = k.field() != null && k.field().startsWith("cf:");
                Comparable va = cf ? customSortValue(s, a, k.field(), parsed) : sortValue(s, a, k.field());
                Comparable vb = cf ? customSortValue(s, b, k.field(), parsed) : sortValue(s, b, k.field());
                int c = va instanceof String x && vb instanceof String y ? Text.KOREAN.compare(x, y) : va.compareTo(vb);
                if (c != 0) return "desc".equals(k.dir()) ? -c : c;
            }
            return Long.compare(a.number(), b.number());
        };
    }

    /* ---------- run ---------- */

    private List<Light> fetchLight(Condition where) {
        return db.select(W.ID, W.NUMBER, W.PARENT_ID, W.PROJECT_ID, W.SUBJECT, W.TYPE_ID, W.STATUS_ID, W.PRIORITY_ID, W.ASSIGNEE_ID, W.RESPONSIBLE_ID,
                        W.AUTHOR_ID, W.START_DATE, W.DUE_DATE, W.UPDATED_AT, W.CUSTOM_VALUES)
                .from(W).where(where).fetch(r -> new Light(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(),
                        r.value9(), r.value10(), r.value11(), r.value12(), r.value13(), r.value14(), r.value15()));
    }

    /** Projects in scope: one project (plus subprojects when asked) or every visible one. */
    private Set<UUID> scope(Ctx ctx, Snapshot.ProjectRow project, boolean includeSubprojects) {
        var out = new HashSet<UUID>();
        for (var p : ctx.s().projects().values()) {
            if (!ctx.can(p, "wp.view")) continue;
            if (project == null) {
                if (p.archivedAt() == null) out.add(p.id());
            } else if (p.id().equals(project.id()) || (includeSubprojects && ctx.s().isDescendant(p.id(), project.id()))) out.add(p.id());
        }
        return out;
    }

    /** Items I may list: those of projects in scope where I have wp.view, plus items shared with me in the projects the list covers. */
    private Condition visible(Ctx ctx, Snapshot.ProjectRow project, boolean includeSubprojects) {
        var projects = scope(ctx, project, includeSubprojects);
        Condition c = projects.isEmpty() ? falseCondition() : W.PROJECT_ID.in(projects);
        var shared = ctx.s().shares(ctx.userId()).roles().keySet();
        if (shared.isEmpty()) return c;
        var covered = ctx.s().projects().values().stream()
                .filter(p -> project == null ? p.archivedAt() == null : p.id().equals(project.id()) || (includeSubprojects && ctx.s().isDescendant(p.id(), project.id())))
                .map(Snapshot.ProjectRow::id).toList();
        return c.or(W.ID.in(shared).and(covered.isEmpty() ? falseCondition() : W.PROJECT_ID.in(covered)));
    }

    public Dto.QueryResult run(Ctx ctx, Spec spec, Snapshot.ProjectRow project, int page, int pageSize) {
        var s = ctx.s();
        page = Math.max(1, page);
        pageSize = Math.min(1000, Math.max(1, pageSize));
        var inScope = visible(ctx, project, spec.includeSubprojects());
        Condition where = inScope;
        for (var f : spec.filters()) where = where.and(condition(ctx, f));
        var text = spec.text() == null ? "" : spec.text().trim();
        if (!text.isEmpty()) {
            var number = text.replace("#", "");
            Condition byNumber = falseCondition();
            try {
                byNumber = W.NUMBER.eq(Long.parseLong(number));
            } catch (NumberFormatException ignored) {
                // text only
            }
            where = where.and(W.SUBJECT.containsIgnoreCase(text).or(byNumber));
        }
        var cmp = comparator(s, spec.sort());
        var matching = new ArrayList<>(fetchLight(where));
        matching.sort(cmp);
        long total = matching.size();

        record Row(UUID id, int depth, boolean hasChildren, boolean context) {}
        var rows = new ArrayList<Row>();
        if (!spec.hierarchy()) {
            int from = Math.min(matching.size(), (page - 1) * pageSize);
            for (var w : matching.subList(from, Math.min(matching.size(), from + pageSize))) rows.add(new Row(w.id(), 0, false, false));
        } else {
            // matching items plus their visible ancestors (context rows), printed as a tree
            var ids = new HashSet<UUID>();
            var shown = new LinkedHashMap<UUID, Light>();
            matching.forEach(w -> {
                ids.add(w.id());
                shown.put(w.id(), w);
            });
            var missing = matching.stream().map(Light::parentId).filter(p -> p != null && !shown.containsKey(p)).collect(java.util.stream.Collectors.toSet());
            for (int i = 0; i < 50 && !missing.isEmpty(); i++) {
                var found = fetchLight(W.ID.in(missing).and(inScope));
                missing = new HashSet<>();
                for (var a : found) shown.put(a.id(), a);
                for (var a : found) if (a.parentId() != null && !shown.containsKey(a.parentId())) missing.add(a.parentId());
            }
            var kids = new HashMap<UUID, List<Light>>();
            var roots = new ArrayList<Light>();
            for (var w : shown.values()) {
                if (w.parentId() == null || !shown.containsKey(w.parentId())) roots.add(w);
                else kids.computeIfAbsent(w.parentId(), k -> new ArrayList<>()).add(w);
            }
            roots.sort(cmp);
            kids.values().forEach(l -> l.sort(cmp));
            var out = new ArrayList<Row>();
            var walk = new Object() {
                void go(Light w, int depth) {
                    var ks = kids.getOrDefault(w.id(), List.of());
                    out.add(new Row(w.id(), depth, !ks.isEmpty(), !ids.contains(w.id())));
                    ks.forEach(k -> go(k, depth + 1));
                }
            };
            roots.forEach(r -> walk.go(r, 0));
            // paginate by matching rows; each page repeats the ancestors its first rows need
            var byId = new HashMap<UUID, Row>();
            out.forEach(r -> byId.put(r.id(), r));
            var pageRows = out.stream().filter(r -> !r.context()).skip((long) (page - 1) * pageSize).limit(pageSize).toList();
            var added = new HashSet<UUID>();
            for (var r : pageRows) {
                var chain = new ArrayList<Row>();
                var parentId = shown.get(r.id()).parentId();
                while (parentId != null && byId.containsKey(parentId) && !added.contains(parentId)) {
                    chain.add(0, byId.get(parentId));
                    parentId = shown.get(parentId).parentId();
                }
                for (var c : chain) {
                    rows.add(c);
                    added.add(c.id());
                }
                rows.add(r);
                added.add(r.id());
            }
        }

        var records = assembler.load(rows.stream().map(Row::id).toList());
        var dtos = assembler.many(ctx, records);
        var byId = new HashMap<UUID, Dto.WorkPackage>();
        dtos.forEach(d -> byId.put(d.id(), d));
        var result = rows.stream().filter(r -> byId.containsKey(r.id()))
                .map(r -> new Dto.QueryRow(byId.get(r.id()), r.depth(), r.hasChildren(), r.context())).toList();

        List<Dto.TimelineLink> links = null;
        if (spec.timeline()) {
            var numbers = new HashMap<UUID, Long>();
            result.forEach(r -> numbers.put(r.wp().id(), r.wp().number()));
            links = numbers.isEmpty() ? List.of() : db.select(RELATIONS.FROM_ID, RELATIONS.TO_ID, RELATIONS.LAG).from(RELATIONS)
                    .where(RELATIONS.TYPE.eq("precedes"), RELATIONS.FROM_ID.in(numbers.keySet()), RELATIONS.TO_ID.in(numbers.keySet()))
                    .fetch(r -> new Dto.TimelineLink(numbers.get(r.value1()), numbers.get(r.value2()), r.value3()));
        }
        return new Dto.QueryResult(result, total, page, pageSize, links);
    }
}

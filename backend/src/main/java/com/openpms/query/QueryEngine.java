package com.openpms.query;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static com.openpms.db.Tables.WORK_PACKAGE_ASSIGNEES;
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
import java.math.BigDecimal;
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

    /** groupBy: a column key (GROUPABLE, or a list/user/bool custom field); sums: totals of the summable columns among {@code columns}. */
    public record Spec(List<Filter> filters, List<Sort> sort, boolean hierarchy, boolean includeSubprojects, String text, boolean timeline, String groupBy,
                       boolean sums, List<String> columns) {}

    /** Columns a list can be grouped by (plus list, user and yes/no custom fields). */
    public static final Set<String> GROUPABLE = Set.of("type", "status", "priority", "assignee", "responsible", "author", "version", "project");

    private static final Map<String, Set<String>> OPS = new HashMap<>();

    static {
        var ref = Set.of("eq", "neq", "any", "none");
        var date = Set.of("overdue", "today", "this_week", "next_days", "last_days", "on", "between", "any", "none");
        var num = Set.of("eq", "gte", "lte", "any", "none");
        var text = Set.of("contains", "not_contains");
        OPS.put("status", Set.of("open", "closed", "eq", "neq"));
        for (var f : List.of("type", "priority", "assignee", "responsible", "author", "version", "category", "project")) OPS.put(f, ref);
        for (var f : List.of("watcher", "assigneeGroup", "assigneeRole", "number")) OPS.put(f, Set.of("eq", "neq"));
        // 서비스 데스크 티켓: facts of the ticket now (the baseline has no history of them)
        for (var f : List.of("requester", "assignmentGroup", "catalogItem")) OPS.put(f, Set.of("eq", "neq"));
        OPS.put("approvalState", Set.of("eq"));
        for (var f : List.of("channel", "slaBreached")) OPS.put(f, Set.of("eq"));
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

    /**
     * 담당자 filters look at everyone who has the item (공동 담당자), not only the main assignee. The baseline's table of
     * past values only knows the main assignee then, so there it stays that.
     */
    private Condition team(WorkPackages t, String op, java.util.Collection<UUID> people, boolean none) {
        if (t != W) {
            var in = people.isEmpty() ? falseCondition() : t.ASSIGNEE_ID.in(people);
            return switch (op) {
                case "eq" -> in.or(none ? t.ASSIGNEE_ID.isNull() : falseCondition());
                case "neq" -> in.not().or(t.ASSIGNEE_ID.isNull()).and(none ? t.ASSIGNEE_ID.isNotNull() : trueCondition());
                case "any" -> t.ASSIGNEE_ID.isNotNull();
                default -> t.ASSIGNEE_ID.isNull();
            };
        }
        var A = WORK_PACKAGE_ASSIGNEES;
        var anyone = exists(selectOne().from(A).where(A.WORK_PACKAGE_ID.eq(t.ID)));
        var these = people.isEmpty() ? falseCondition() : exists(selectOne().from(A).where(A.WORK_PACKAGE_ID.eq(t.ID), A.USER_ID.in(people)));
        return switch (op) {
            case "eq" -> these.or(none ? anyone.not() : falseCondition());
            case "neq" -> these.not().and(none ? anyone : trueCondition());
            case "any" -> anyone;
            default -> anyone.not();
        };
    }

    private Condition date(Field<LocalDate> col, String op, List<String> vals, LocalDate today, Set<UUID> closed, WorkPackages t) {
        int n = 7;
        try {
            if (!vals.isEmpty()) n = Math.max(1, Integer.parseInt(vals.get(0)));
        } catch (NumberFormatException ignored) {
            // default 7 days
        }
        return switch (op) {
            case "any" -> col.isNotNull();
            case "none" -> col.isNull();
            case "overdue" -> col.lt(today).and(closed.isEmpty() ? trueCondition() : t.STATUS_ID.notIn(closed));
            case "today" -> col.eq(today);
            // the week as the calendars show it (관리 · 날짜 형식 · 주 시작 요일)
            case "this_week" -> col.between(com.openpms.common.Dates.startOfWeek(today), com.openpms.common.Dates.startOfWeek(today).plusDays(6));
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
    private Condition custom(Ctx ctx, Snapshot.CustomFieldRow cf, String op, List<String> vals, Set<UUID> closed, WorkPackages t) {
        var s = ctx.s();
        var id = cf.id().toString();
        var on = s.projects().values().stream().filter(p -> s.customFieldEnabledIn(cf, p.id())).map(Snapshot.ProjectRow::id).toList();
        Condition active = (cf.typeIds().isEmpty() ? falseCondition() : t.TYPE_ID.in(cf.typeIds()))
                .and(cf.isForAll() ? trueCondition() : on.isEmpty() ? falseCondition() : t.PROJECT_ID.in(on));
        Condition has = active.and(DSL.condition("jsonb_exists({0}, {1})", t.CUSTOM_VALUES, DSL.val(id)));
        if ("any".equals(op)) return has;
        if ("none".equals(op)) return has.not();
        Field<JSONB> raw = DSL.field("({0} -> {1})", SQLDataType.JSONB, t.CUSTOM_VALUES, DSL.val(id));
        String needle = vals.isEmpty() || vals.get(0) == null ? "" : vals.get(0);
        return switch (cf.format()) {
            case "list", "user" -> {
                var ids = vals.stream().filter(v -> v != null && !v.isEmpty()).toArray(String[]::new);
                // jsonb_exists_any matches a string value or any element of an array of them
                Condition hit = ids.length == 0 ? falseCondition() : has.and(DSL.condition("jsonb_exists_any({0}, {1}::text[])", raw, DSL.val(ids)));
                yield "neq".equals(op) ? hit.not() : hit;
            }
            case "integer", "float" -> has.and(num(DSL.field("({0} ->> {1})::numeric", SQLDataType.NUMERIC, t.CUSTOM_VALUES, DSL.val(id)), op, vals));
            case "date" -> has.and(date(DSL.field("({0} ->> {1})::date", SQLDataType.LOCALDATE, t.CUSTOM_VALUES, DSL.val(id)), op, vals, ctx.today(), closed, t));
            case "bool" -> has.and(DSL.condition("({0} -> {1}) = {2}::jsonb", t.CUSTOM_VALUES, DSL.val(id), DSL.val("true".equals(needle) ? "true" : "false")));
            default -> {
                Condition hit = has.and(DSL.field("({0} ->> {1})", SQLDataType.VARCHAR, t.CUSTOM_VALUES, DSL.val(id)).containsIgnoreCase(needle));
                yield "not_contains".equals(op) ? hit.not() : hit;
            }
        };
    }

    /** Filters as JSON ([{field, op, values}]) → records; anything else is no filter. */
    public static List<Filter> filters(Object raw) {
        var out = new ArrayList<Filter>();
        if (raw instanceof List<?> fs) for (var f : fs) {
            if (!(f instanceof Map<?, ?> x)) continue;
            List<String> values = x.get("values") instanceof List<?> l ? l.stream().map(v -> v == null ? null : String.valueOf(v)).toList() : List.of();
            out.add(new Filter(String.valueOf(x.get("field")), String.valueOf(x.get("op")), values));
        }
        return out;
    }

    /** All of these filters, as one condition on work_packages (boards use the list's fields and operators). */
    public Condition where(Ctx ctx, List<Filter> filters) {
        return where(ctx, filters, W);
    }

    /** The same filters on another table shaped like work_packages (기준선: a copy holding the values items had then). */
    public Condition where(Ctx ctx, List<Filter> filters, WorkPackages t) {
        Condition c = noCondition();
        for (var f : filters) c = c.and(condition(ctx, f, t));
        return c;
    }

    Condition condition(Ctx ctx, Filter f) {
        return condition(ctx, f, W);
    }

    Condition condition(Ctx ctx, Filter f, WorkPackages t) {
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
            return custom(ctx, cf, f.op(), vals, closed, t);
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
                case "open" -> closed.isEmpty() ? trueCondition() : t.STATUS_ID.notIn(closed);
                case "closed" -> closed.isEmpty() ? falseCondition() : t.STATUS_ID.in(closed);
                default -> ref(t.STATUS_ID, f.op(), vals);
            };
            case "type" -> ref(t.TYPE_ID, f.op(), vals);
            case "priority" -> ref(t.PRIORITY_ID, f.op(), vals);
            case "assignee" -> team(t, f.op(), vals.stream().map(QueryEngine::uuid).filter(x -> x != null).toList(), vals.contains("none"));
            case "responsible" -> ref(t.RESPONSIBLE_ID, f.op(), vals);
            case "author" -> ref(t.AUTHOR_ID, f.op(), vals);
            case "version" -> ref(t.VERSION_ID, f.op(), vals);
            case "category" -> ref(t.CATEGORY_ID, f.op(), vals);
            case "project" -> ref(t.PROJECT_ID, f.op(), vals);
            case "requester", "assignmentGroup", "channel", "catalogItem" -> {
                var T = SERVICE_TICKETS;
                var column = switch (f.field()) {
                    case "requester" -> T.REQUESTER_ID.cast(String.class);
                    case "assignmentGroup" -> T.ASSIGNMENT_GROUP_ID.cast(String.class);
                    case "catalogItem" -> T.CATALOG_ITEM_ID.cast(String.class);
                    default -> T.CHANNEL;
                };
                var hit = exists(selectOne().from(T).where(T.WORK_PACKAGE_ID.eq(t.ID), column.in(vals)));
                yield "neq".equals(f.op()) ? hit.not() : hit;
            }
            case "approvalState" -> {
                // pending: a decision is awaited; delayed: that approver was already reminded; rejected; none: the request never had approval rows
                var A = TICKET_APPROVALS;
                if (vals.size() != 1) throw ApiException.unprocessable("필터를 확인하세요.", "승인 상태는 하나만 고를 수 있습니다.");
                var mine = A.WORK_PACKAGE_ID.eq(t.ID);
                yield switch (needle) {
                    case "pending" -> exists(selectOne().from(A).where(mine, A.DECISION.eq("pending")));
                    case "delayed" -> exists(selectOne().from(A).where(mine, A.DECISION.eq("pending"), A.REMINDED_AT.isNotNull()));
                    case "rejected" -> exists(selectOne().from(A).where(mine, A.DECISION.eq("rejected")));
                    case "none" -> notExists(selectOne().from(A).where(mine));
                    default -> throw ApiException.unprocessable("필터를 확인하세요.", "승인 상태는 pending, delayed, rejected, none 중 하나여야 합니다.");
                };
            }
            case "slaBreached" -> {
                var T = SERVICE_TICKETS;
                var hit = exists(selectOne().from(T).where(T.WORK_PACKAGE_ID.eq(t.ID), T.RESPONSE_BREACHED.eq(true).or(T.RESOLVE_BREACHED.eq(true))));
                yield yes ? hit : hit.not();
            }
            case "watcher" -> {
                var ids = vals.stream().map(QueryEngine::uuid).filter(x -> x != null).toList();
                var watched = selectOne().from(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.eq(t.ID), WATCHERS.USER_ID.in(ids));
                yield "eq".equals(f.op()) ? exists(watched) : notExists(watched);
            }
            case "assigneeGroup" -> {
                var people = new HashSet<UUID>();
                vals.stream().map(QueryEngine::uuid).filter(x -> x != null && s.groups().containsKey(x)).forEach(g -> people.addAll(s.groups().get(g).memberIds()));
                yield team(t, "eq".equals(f.op()) ? "eq" : "neq", people, false);
            }
            case "assigneeRole" -> {
                var roles = vals.stream().map(QueryEngine::uuid).filter(x -> x != null).toList();
                var parts = new ArrayList<Condition>();
                for (var p : s.projects().values()) {
                    var people = s.projectUsers(p.id()).stream().map(Snapshot.UserRow::id)
                            .filter(u -> s.memberRoleIds(u, p.id()).stream().anyMatch(roles::contains)).toList();
                    if (!people.isEmpty()) parts.add(t.PROJECT_ID.eq(p.id()).and(team(t, "eq", people, false)));
                }
                var in = parts.isEmpty() ? falseCondition() : or(parts);
                yield "eq".equals(f.op()) ? in : in.not();
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
                yield "eq".equals(f.op()) ? (nums.isEmpty() ? falseCondition() : t.NUMBER.in(nums)) : (nums.isEmpty() ? trueCondition() : t.NUMBER.notIn(nums));
            }
            case "parent" -> switch (f.op()) {
                case "any" -> t.PARENT_ID.isNotNull();
                case "none" -> t.PARENT_ID.isNull();
                default -> {
                    long n;
                    try {
                        n = Long.parseLong(needle.replace("#", "").trim());
                    } catch (NumberFormatException e) {
                        yield falseCondition();
                    }
                    var p = WORK_PACKAGES.as("p");
                    yield t.PARENT_ID.in(db.select(p.ID).from(p).where(p.NUMBER.eq(n)));
                }
            };
            case "subject" -> text(t.SUBJECT, f.op(), needle);
            case "description" -> text(t.DESCRIPTION_TEXT, f.op(), needle);
            case "comment" -> {
                var hit = selectOne().from(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(t.ID), JOURNALS.NOTES_TEXT.containsIgnoreCase(needle));
                yield "not_contains".equals(f.op()) ? notExists(hit) : exists(hit);
            }
            case "isMilestone" -> {
                var ms = s.types().stream().filter(Snapshot.TypeRow::isMilestone).map(Snapshot.TypeRow::id).toList();
                var in = ms.isEmpty() ? falseCondition() : t.TYPE_ID.in(ms);
                yield yes ? in : in.not();
            }
            case "scheduleManually" -> t.SCHEDULE_MANUALLY.eq(yes);
            case "blocked" -> {
                var b = WORK_PACKAGES.as("b");
                var blocker = selectOne().from(RELATIONS).join(b).on(b.ID.eq(RELATIONS.FROM_ID))
                        .where(RELATIONS.TYPE.eq("blocks"), RELATIONS.TO_ID.eq(t.ID), closed.isEmpty() ? trueCondition() : b.STATUS_ID.notIn(closed));
                yield yes ? exists(blocker) : notExists(blocker);
            }
            case "dueDate" -> date(t.DUE_DATE, f.op(), vals, today, closed, t);
            case "startDate" -> date(t.START_DATE, f.op(), vals, today, closed, t);
            case "createdAt" -> date(t.CREATED_AT.cast(LocalDate.class), f.op(), vals, today, closed, t);
            case "updatedAt" -> date(t.UPDATED_AT.cast(LocalDate.class), f.op(), vals, today, closed, t);
            case "duration" -> num(t.DURATION, f.op(), vals);
            case "doneRatio" -> num(t.DONE_RATIO, f.op(), vals);
            case "work" -> num(t.WORK_HOURS, f.op(), vals);
            case "remainingWork" -> num(t.REMAINING_HOURS, f.op(), vals);
            case "dates" -> {
                var start = coalesce(t.START_DATE, t.DUE_DATE);
                var end = coalesce(t.DUE_DATE, t.START_DATE);
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
                 UUID responsibleId, UUID authorId, LocalDate startDate, LocalDate dueDate, OffsetDateTime updatedAt, JSONB customValues, UUID versionId,
                 BigDecimal workHours, BigDecimal remainingHours, Integer doneRatio, OffsetDateTime slaDue) {}

    /** Sort key of a custom field: empty (or not on for the item) sorts last. Parsed values are cached per run. */
    private Comparable<?> customSortValue(Snapshot s, Light w, String field, Map<UUID, Map<String, Object>> parsed) {
        var cfId = CustomFields.idOf(field);
        var cf = cfId == null ? null : s.customField(cfId).orElse(null);
        if (cf == null) return w.number();
        boolean active = cf.typeIds().contains(w.typeId()) && s.customFieldEnabledIn(cf, w.projectId());
        var v = active ? parsed.computeIfAbsent(w.id(), k -> customFields.read(w.customValues())).get(cf.id().toString()) : null;
        return customFields.sortKey(s, cf, v);
    }

    /** A user's or a group's name (the assignee may be a group). */
    private static String principalName(Snapshot s, UUID id) {
        return s.user(id).map(Snapshot.UserRow::name).orElseGet(() -> s.groups().containsKey(id) ? s.groups().get(id).name() : "");
    }

    private static Comparable<?> sortValue(Snapshot s, Light w, String field) {
        java.util.function.Function<UUID, String> name = id -> id == null ? "￿" : principalName(s, id);
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
            case "slaDue" -> w.slaDue() == null ? "9999" : w.slaDue().toInstant().toString();
            case "project" -> s.project(w.projectId()).map(Snapshot.ProjectRow::name).orElse("");
            case "version" -> w.versionId() == null ? "￿" : s.version(w.versionId()).map(Snapshot.VersionRow::name).orElse("");
            case "work" -> w.workHours() == null ? Double.MAX_VALUE : w.workHours().doubleValue();
            case "remainingWork" -> w.remainingHours() == null ? Double.MAX_VALUE : w.remainingHours().doubleValue();
            default -> w.number();
        };
    }

    /* ---------- groups and sums ---------- */

    record Group(String key, String label, String color) {}

    private static final Group NONE = new Group("", "", null);

    /** The custom field a groupBy names, when it is one a list can be grouped by. */
    private Snapshot.CustomFieldRow groupField(Snapshot s, String groupBy) {
        var id = CustomFields.idOf(groupBy);
        var cf = id == null ? null : s.customField(id).orElse(null);
        return cf != null && Set.of("list", "user", "bool").contains(cf.format()) ? cf : null;
    }

    private Group groupOf(Snapshot s, Light w, String by, Snapshot.CustomFieldRow cf, Map<UUID, Map<String, Object>> parsed) {
        if (cf != null) {
            boolean active = cf.typeIds().contains(w.typeId()) && s.customFieldEnabledIn(cf, w.projectId());
            var v = active ? parsed.computeIfAbsent(w.id(), k -> customFields.read(w.customValues())).get(cf.id().toString()) : null;
            var label = customFields.text(s, cf, v);
            if (label.isEmpty()) return NONE;
            var key = v instanceof java.util.Collection<?> c ? String.join(",", c.stream().map(String::valueOf).sorted().toList()) : String.valueOf(v);
            return new Group(key, label, null);
        }
        UUID id = switch (by) {
            case "type" -> w.typeId();
            case "status" -> w.statusId();
            case "priority" -> w.priorityId();
            case "assignee" -> w.assigneeId();
            case "responsible" -> w.responsibleId();
            case "author" -> w.authorId();
            case "version" -> w.versionId();
            case "project" -> w.projectId();
            default -> null;
        };
        if (id == null) return NONE;
        return switch (by) {
            case "type" -> s.type(id).map(t -> new Group(id.toString(), t.name(), t.color())).orElse(NONE);
            case "status" -> s.status(id).map(t -> new Group(id.toString(), t.name(), t.color())).orElse(NONE);
            case "priority" -> s.priority(id).map(t -> new Group(id.toString(), t.name(), t.color())).orElse(NONE);
            case "version" -> s.version(id).map(v -> new Group(id.toString(), v.name(), null)).orElse(NONE);
            case "project" -> s.project(id).map(p -> new Group(id.toString(), p.name(), null)).orElse(NONE);
            default -> new Group(id.toString(), principalName(s, id), null);
        };
    }

    /** Summable columns among the visible ones: work, remaining work, % complete (derived), number custom fields. */
    private List<String> summable(Snapshot s, List<String> columns) {
        return columns.stream().filter(c -> switch (c) {
            case "work", "remainingWork", "doneRatio" -> true;
            default -> {
                var id = CustomFields.idOf(c);
                yield id != null && s.customField(id).map(f -> f.format().equals("integer") || f.format().equals("float")).orElse(false);
            }
        }).distinct().toList();
    }

    /**
     * Adds one item to running totals. Work and remaining work add up every listed item. % complete follows the
     * progress rules (관리 · 진행률 추적) like an item's ∑ totals: items with a status excluded from totals are left
     * out, and it is weighted by work or a plain average of the items' % ("%work", "%remaining", "%sum", "%count").
     */
    private void accumulate(Snapshot s, Light w, List<String> cols, Map<String, BigDecimal> acc, Map<UUID, Map<String, Object>> parsed) {
        if (w.workHours() != null) acc.merge("work", w.workHours(), BigDecimal::add);
        if (w.remainingHours() != null) acc.merge("remainingWork", w.remainingHours(), BigDecimal::add);
        if (cols.contains("doneRatio") && !s.status(w.statusId()).map(Snapshot.StatusRow::excludedFromTotals).orElse(false)) {
            if (w.workHours() != null) {
                acc.merge("%work", w.workHours(), BigDecimal::add);
                // no remaining work recorded: nothing done yet (Progress.totals)
                acc.merge("%remaining", w.remainingHours() == null ? w.workHours() : w.remainingHours(), BigDecimal::add);
            }
            if (w.doneRatio() != null) {
                acc.merge("%sum", BigDecimal.valueOf(w.doneRatio()), BigDecimal::add);
                acc.merge("%count", BigDecimal.ONE, BigDecimal::add);
            }
        }
        for (var c : cols) {
            var id = CustomFields.idOf(c);
            if (id == null) continue;
            var cf = s.customField(id).orElse(null);
            if (cf == null || !cf.typeIds().contains(w.typeId()) || !s.customFieldEnabledIn(cf, w.projectId())) continue;
            var v = parsed.computeIfAbsent(w.id(), k -> customFields.read(w.customValues())).get(id.toString());
            if (v == null || "".equals(v)) continue;
            try {
                acc.merge(c, new BigDecimal(String.valueOf(v)), BigDecimal::add);
            } catch (NumberFormatException ignored) {
                // not a number: left out of the sum
            }
        }
    }

    /** The totals to show: the visible summable columns; % complete as the progress settings total it. */
    private static Map<String, BigDecimal> finish(Snapshot s, Map<String, BigDecimal> acc, List<String> cols) {
        var out = new LinkedHashMap<String, BigDecimal>();
        for (var c : cols) {
            if (c.equals("doneRatio")) {
                if ("weighted".equals(s.settings().progress().totals())) {
                    var work = acc.get("%work");
                    var remaining = acc.getOrDefault("%remaining", BigDecimal.ZERO);
                    out.put(c, work == null || work.signum() == 0 ? null
                            : work.subtract(remaining).multiply(BigDecimal.valueOf(100)).divide(work, 0, java.math.RoundingMode.HALF_UP).max(BigDecimal.ZERO));
                } else {
                    var count = acc.get("%count");
                    out.put(c, count == null ? null : acc.get("%sum").divide(count, 0, java.math.RoundingMode.HALF_UP));
                }
            } else {
                out.put(c, acc.get(c));
            }
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Comparable va, Comparable vb) {
        return va instanceof String x && vb instanceof String y ? Text.KOREAN.compare(x, y) : va.compareTo(vb);
    }

    private Comparator<Light> comparator(Snapshot s, List<Sort> sort) {
        var keys = sort == null || sort.isEmpty() ? List.of(new Sort("number", "asc")) : sort;
        var parsed = new HashMap<UUID, Map<String, Object>>();
        return (a, b) -> {
            for (var k : keys) {
                boolean cf = k.field() != null && k.field().startsWith("cf:");
                int c = compareValues(cf ? customSortValue(s, a, k.field(), parsed) : sortValue(s, a, k.field()),
                        cf ? customSortValue(s, b, k.field(), parsed) : sortValue(s, b, k.field()));
                if (c != 0) return "desc".equals(k.dir()) ? -c : c;
            }
            return Long.compare(a.number(), b.number());
        };
    }

    /* ---------- run ---------- */

    private List<Light> fetchLight(Condition where) {
        return db.select(W.ID, W.NUMBER, W.PARENT_ID, W.PROJECT_ID, W.SUBJECT, W.TYPE_ID, W.STATUS_ID, W.PRIORITY_ID, W.ASSIGNEE_ID, W.RESPONSIBLE_ID,
                        W.AUTHOR_ID, W.START_DATE, W.DUE_DATE, W.UPDATED_AT, W.CUSTOM_VALUES, W.VERSION_ID, W.WORK_HOURS, W.REMAINING_HOURS, W.DONE_RATIO,
                        SERVICE_TICKETS.RESOLVE_DUE_AT)
                .from(W).leftJoin(SERVICE_TICKETS).on(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(W.ID)).where(where).fetch(r -> new Light(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(),
                        r.value9(), r.value10(), r.value11(), r.value12(), r.value13(), r.value14(), r.value15(), r.value16(), r.value17(), r.value18(),
                        r.value19() == null ? null : r.value19().intValue(), r.value20()));
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

    /** The list's quick search: subject contains the text, or the number is it ("#12" or "12"). */
    public static Condition text(String raw, WorkPackages t) {
        var text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return noCondition();
        Condition byNumber = falseCondition();
        try {
            byNumber = t.NUMBER.eq(Long.parseLong(text.replace("#", "")));
        } catch (NumberFormatException ignored) {
            // text only
        }
        return t.SUBJECT.containsIgnoreCase(text).or(byNumber);
    }

    /** Projects a list covers for me (wp.view): the project, its subprojects when asked, or every visible one. */
    public Set<UUID> scopeIds(Ctx ctx, Snapshot.ProjectRow project, boolean includeSubprojects) {
        return scope(ctx, project, includeSubprojects);
    }

    /** Every item the list matches now (no paging). */
    public Set<UUID> matchingIds(Ctx ctx, Spec spec, Snapshot.ProjectRow project) {
        var where = visible(ctx, project, spec.includeSubprojects()).and(where(ctx, spec.filters())).and(text(spec.text(), W));
        return new HashSet<>(db.select(W.ID).from(W).where(where).fetch(r -> r.value1()));
    }

    public Dto.QueryResult run(Ctx ctx, Spec spec, Snapshot.ProjectRow project, int page, int pageSize) {
        var s = ctx.s();
        page = Math.max(1, page);
        pageSize = Math.min(1000, Math.max(1, pageSize));
        var inScope = visible(ctx, project, spec.includeSubprojects());
        Condition where = inScope;
        for (var f : spec.filters()) where = where.and(condition(ctx, f));
        where = where.and(text(spec.text(), W));
        var sortCmp = comparator(s, spec.sort());
        var matching = new ArrayList<>(fetchLight(where));
        // grouped: the group decides the order first (its own order: status position, names…; "none" last), then the sort
        var groupCf = spec.groupBy() == null ? null : groupField(s, spec.groupBy());
        var groupBy = spec.groupBy() != null && (GROUPABLE.contains(spec.groupBy()) || groupCf != null) ? spec.groupBy() : null;
        var parsed = new HashMap<UUID, Map<String, Object>>();
        var groupOfRow = new HashMap<UUID, Group>();
        Comparator<Light> cmp = sortCmp;
        if (groupBy != null) {
            for (var w : matching) groupOfRow.put(w.id(), groupOf(s, w, groupBy, groupCf, parsed));
            boolean desc = spec.sort() != null && !spec.sort().isEmpty() && groupBy.equals(spec.sort().get(0).field()) && "desc".equals(spec.sort().get(0).dir());
            Comparator<Light> byGroup = (a, b) -> {
                boolean na = groupOfRow.get(a.id()) == NONE, nb = groupOfRow.get(b.id()) == NONE;
                if (na || nb) return Boolean.compare(na, nb);
                int c = compareValues(groupCf != null ? customSortValue(s, a, groupBy, parsed) : sortValue(s, a, groupBy),
                        groupCf != null ? customSortValue(s, b, groupBy, parsed) : sortValue(s, b, groupBy));
                if (c == 0) c = groupOfRow.get(a.id()).key().compareTo(groupOfRow.get(b.id()).key());
                return desc ? -c : c;
            };
            cmp = byGroup.thenComparing(sortCmp);
        }
        matching.sort(cmp);
        final var treeCmp = cmp; // the hierarchy below sorts siblings with the same order
        long total = matching.size();

        var sumCols = spec.sums() ? summable(s, spec.columns()) : List.<String>of();
        List<Dto.QueryGroup> groups = null;
        if (groupBy != null) {
            var counts = new LinkedHashMap<String, long[]>();
            var labels = new LinkedHashMap<String, Group>();
            var accs = new HashMap<String, Map<String, BigDecimal>>();
            for (var w : matching) {
                var g = groupOfRow.get(w.id());
                labels.putIfAbsent(g.key(), g);
                counts.computeIfAbsent(g.key(), k -> new long[1])[0]++;
                if (spec.sums()) accumulate(s, w, sumCols, accs.computeIfAbsent(g.key(), k -> new HashMap<>()), parsed);
            }
            groups = labels.values().stream().map(g -> new Dto.QueryGroup(g.key(), g.label(), g.color(), counts.get(g.key())[0],
                    spec.sums() ? finish(s, accs.getOrDefault(g.key(), Map.of()), sumCols) : null)).toList();
        }
        Map<String, BigDecimal> sums = null;
        if (spec.sums()) {
            var acc = new HashMap<String, BigDecimal>();
            for (var w : matching) accumulate(s, w, sumCols, acc, parsed);
            sums = finish(s, acc, sumCols);
        }

        record Row(UUID id, int depth, boolean hasChildren, boolean context) {}
        var rows = new ArrayList<Row>();
        // a grouped list is flat (the original turns hierarchy off while grouping)
        if (!spec.hierarchy() || groupBy != null) {
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
            roots.sort(treeCmp);
            kids.values().forEach(l -> l.sort(treeCmp));
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
                .map(r -> new Dto.QueryRow(byId.get(r.id()), r.depth(), r.hasChildren(), r.context(),
                        groupOfRow.containsKey(r.id()) ? groupOfRow.get(r.id()).key() : null, null)).toList();

        List<Dto.TimelineLink> links = null;
        if (spec.timeline()) {
            var numbers = new HashMap<UUID, Long>();
            result.forEach(r -> numbers.put(r.wp().id(), r.wp().number()));
            links = numbers.isEmpty() ? List.of() : db.select(RELATIONS.FROM_ID, RELATIONS.TO_ID, RELATIONS.LAG).from(RELATIONS)
                    .where(RELATIONS.TYPE.eq("precedes"), RELATIONS.FROM_ID.in(numbers.keySet()), RELATIONS.TO_ID.in(numbers.keySet()))
                    .fetch(r -> new Dto.TimelineLink(numbers.get(r.value1()), numbers.get(r.value2()), r.value3()));
        }
        return new Dto.QueryResult(result, total, page, pageSize, links, groups, sums, null, null);
    }
}

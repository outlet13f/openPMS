package com.openpms.query;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot;
import com.openpms.customfield.CustomFields;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.wp.Journals;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Service;

/**
 * S06 기준선: how the listed work packages looked at a point in the past. The value a field had then is the "from" of its
 * first change after that point (journals keep [from, to] per field); an item made after it is new. Items that matched the
 * list then but no longer do come back as "removed": their values then go into a temporary copy of work_packages and the
 * list's own filters run on it, so "matched then" means exactly what the list means now. Not covered: deleted items, items
 * I could see then only through a share, and filters on things journals do not keep (watchers, comments, relations), which
 * use today's state.
 */
@Service
public class Baselines {
    /** Journal field → list column. */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("subject", "subject"), Map.entry("typeId", "type"), Map.entry("statusId", "status"), Map.entry("priorityId", "priority"),
            Map.entry("assigneeId", "assignee"), Map.entry("responsibleId", "responsible"), Map.entry("versionId", "version"),
            Map.entry("startDate", "startDate"), Map.entry("dueDate", "dueDate"), Map.entry("duration", "duration"), Map.entry("doneRatio", "doneRatio"),
            Map.entry("workHours", "work"), Map.entry("remainingHours", "remainingWork"), Map.entry("projectId", "project"));

    /** Journal field → work_packages column (custom fields "cf:<id>" go into custom_values). */
    private static final Map<String, String> COLUMN_NAMES = Map.ofEntries(
            Map.entry("subject", "subject"), Map.entry("projectId", "project_id"), Map.entry("typeId", "type_id"), Map.entry("statusId", "status_id"),
            Map.entry("priorityId", "priority_id"), Map.entry("assigneeId", "assignee_id"), Map.entry("responsibleId", "responsible_id"),
            Map.entry("versionId", "version_id"), Map.entry("categoryId", "category_id"), Map.entry("parentId", "parent_id"),
            Map.entry("startDate", "start_date"), Map.entry("dueDate", "due_date"), Map.entry("duration", "duration"),
            Map.entry("scheduleManually", "schedule_manually"), Map.entry("ignoreNonWorkingDays", "ignore_non_working_days"),
            Map.entry("workHours", "work_hours"), Map.entry("remainingHours", "remaining_hours"), Map.entry("doneRatio", "done_ratio"));

    /** At most this many removed items come back (the most recent numbers first are not special: lowest numbers first). */
    static final int REMOVED_LIMIT = 200;
    private static final int CANDIDATE_LIMIT = 5000;

    private final DSLContext db;
    private final Journals journals;
    private final CustomFields customFields;
    private final QueryEngine engine;
    private final tools.jackson.databind.json.JsonMapper json;

    public Baselines(DSLContext db, Journals journals, CustomFields customFields, QueryEngine engine, tools.jackson.databind.json.JsonMapper json) {
        this.json = json;
        this.db = db;
        this.journals = journals;
        this.customFields = customFields;
        this.engine = engine;
    }

    /** The point a spec's baseline names ({kind: yesterday | lastWeek | lastMonth | date, date?}), at the start of that day. */
    public static OffsetDateTime at(Object raw, LocalDate today, String timeZone) {
        if (!(raw instanceof Map<?, ?> m) || m.get("kind") == null) return null;
        LocalDate day = switch (String.valueOf(m.get("kind"))) {
            case "yesterday" -> today.minusDays(1);
            case "lastWeek" -> today.minusWeeks(1);
            case "lastMonth" -> today.minusMonths(1);
            case "date" -> {
                try {
                    yield LocalDate.parse(String.valueOf(m.get("date")));
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            default -> null;
        };
        if (day == null || day.isAfter(today)) return null;
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (RuntimeException e) {
            zone = ZoneId.of("Asia/Seoul");
        }
        return day.atStartOfDay(zone).toOffsetDateTime();
    }

    /** Per item: each changed field's value at the point (the first "from") and today (the last "to"), changed-and-back left out. */
    private Map<UUID, Map<String, Object[]>> history(List<UUID> ids, OffsetDateTime at) {
        var out = new HashMap<UUID, Map<String, Object[]>>();
        if (ids.isEmpty()) return out;
        db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.in(ids), JOURNALS.CREATED_AT.ge(at)).orderBy(JOURNALS.VERSION).fetch().forEach(j -> {
            var fields = out.computeIfAbsent(j.getWorkPackageId(), k -> new LinkedHashMap<>());
            journals.changesOf(j).forEach((field, v) -> {
                if (v == null || v.isEmpty()) return;
                var fromTo = fields.computeIfAbsent(field, k -> new Object[] {v.get(0), null});
                fromTo[1] = v.size() > 1 ? v.get(1) : null;
            });
        });
        out.values().forEach(fields -> fields.values().removeIf(ft -> Objects.equals(String.valueOf(ft[0]), String.valueOf(ft[1]))));
        return out;
    }

    /** A changed field's list column and its text then (side 0) or today (side 1); fields that are not list columns are left out. */
    private Map<String, String> columns(Snapshot s, Map<String, Object[]> fields, int side) {
        var out = new LinkedHashMap<String, String>();
        fields.forEach((field, ft) -> {
            var cf = CustomFields.idOf(field);
            if (cf != null) s.customField(cf).ifPresent(f -> out.put("cf:" + cf, blank(customFields.text(s, f, ft[side]))));
            else if (COLUMNS.containsKey(field)) out.put(COLUMNS.get(field), journals.display(s, field, ft[side]));
        });
        return out;
    }

    /**
     * Items the list matched at the point but does not now: made before it, changed since, in a project I can see today, and
     * passing the list's filters (and quick search) with the values they had then, in a project the list covered.
     */
    public List<Dto.BaselineRemoved> removed(Ctx ctx, QueryEngine.Spec spec, Snapshot.ProjectRow project, OffsetDateTime at) {
        var s = ctx.s();
        var viewable = ctx.visibleProjects("wp.view").stream().map(Snapshot.ProjectRow::id).toList();
        if (viewable.isEmpty()) return List.of();
        var now = engine.matchingIds(ctx, spec, project);
        var candidates = db.selectDistinct(JOURNALS.WORK_PACKAGE_ID).from(JOURNALS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(JOURNALS.WORK_PACKAGE_ID))
                .where(JOURNALS.CREATED_AT.ge(at), WORK_PACKAGES.CREATED_AT.lt(at), WORK_PACKAGES.PROJECT_ID.in(viewable))
                .limit(CANDIDATE_LIMIT).fetch(r -> r.value1()).stream().filter(id -> !now.contains(id)).toList();
        var changes = history(candidates, at);
        changes.values().removeIf(Map::isEmpty);
        if (changes.isEmpty()) return List.of();
        var scope = engine.scopeIds(ctx, project, spec.includeSubprojects());
        if (scope.isEmpty()) return List.of();

        var then = WORK_PACKAGES.as("wp_then");
        var matched = db.transactionResult(cfg -> {
            var tx = DSL.using(cfg);
            var table = DSL.table(DSL.name("wp_then"));
            tx.createTemporaryTable(table).as(DSL.select().from(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(changes.keySet()))).onCommitDrop().execute();
            changes.forEach((id, fields) -> fields.forEach((field, ft) -> {
                var where = DSL.field(DSL.name("id"), SQLDataType.UUID).eq(id);
                var cf = CustomFields.idOf(field);
                var custom = DSL.field(DSL.name("custom_values"), SQLDataType.JSONB);
                if (cf != null) {
                    var value = ft[0] == null ? DSL.field("{0} - {1}", SQLDataType.JSONB, custom, DSL.val(cf.toString()))
                            : DSL.field("jsonb_set({0}, array[{1}], {2}::jsonb)", SQLDataType.JSONB, custom, DSL.val(cf.toString()), DSL.val(JSONB.valueOf(json(ft[0]))));
                    tx.update(table).set(custom, value).where(where).execute();
                } else if ("descriptionHtml".equals(field)) {
                    var html = ft[0] == null ? "" : String.valueOf(ft[0]);
                    tx.update(table).set(DSL.field(DSL.name("description_html"), SQLDataType.CLOB), html)
                            .set(DSL.field(DSL.name("description_text"), SQLDataType.CLOB), com.openpms.common.Html.text(html)).where(where).execute();
                } else if (COLUMN_NAMES.containsKey(field)) {
                    var col = WORK_PACKAGES.field(COLUMN_NAMES.get(field));
                    @SuppressWarnings("unchecked")
                    var target = (org.jooq.Field<Object>) DSL.field(DSL.name(col.getName()), col.getDataType());
                    tx.update(table).set(target, ft[0] == null ? null : DSL.val(ft[0], col.getDataType())).where(where).execute();
                }
            }));
            return new HashSet<>(tx.select(then.ID).from(table)
                    .where(then.PROJECT_ID.in(scope), engine.where(ctx, spec.filters(), then), QueryEngine.text(spec.text(), then)).fetch(r -> r.value1()));
        });
        if (matched.isEmpty()) return List.of();
        return db.select(WORK_PACKAGES.ID, WORK_PACKAGES.NUMBER, WORK_PACKAGES.SUBJECT, WORK_PACKAGES.PROJECT_ID).from(WORK_PACKAGES)
                .where(WORK_PACKAGES.ID.in(matched)).orderBy(WORK_PACKAGES.NUMBER).limit(REMOVED_LIMIT).fetch(r -> {
                    var fields = changes.get(r.value1());
                    var p = s.project(r.value4()).map(x -> new Dto.ProjectRef(x.id(), x.identifier(), x.name())).orElse(null);
                    return new Dto.BaselineRemoved(r.value1(), r.value2(), r.value3(), p, columns(s, fields, 0), columns(s, fields, 1));
                });
    }

    private String json(Object v) {
        return json.writeValueAsString(v);
    }

    public Map<UUID, Dto.RowBaseline> of(Ctx ctx, List<Dto.WorkPackage> wps, OffsetDateTime at) {
        var s = ctx.s();
        var out = new HashMap<UUID, Dto.RowBaseline>();
        if (wps.isEmpty()) return out;
        var ids = wps.stream().map(Dto.WorkPackage::id).toList();
        var byWp = new HashMap<UUID, List<JournalsRecord>>();
        db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.in(ids), JOURNALS.CREATED_AT.ge(at)).orderBy(JOURNALS.VERSION)
                .fetch().forEach(j -> byWp.computeIfAbsent(j.getWorkPackageId(), k -> new ArrayList<>()).add(j));
        for (var wp : wps) {
            if (wp.createdAt().isAfter(at)) {
                out.put(wp.id(), new Dto.RowBaseline(true, Map.of()));
                continue;
            }
            var first = new LinkedHashMap<String, Object>();
            var last = new HashMap<String, Object>();
            for (var j : byWp.getOrDefault(wp.id(), List.of())) {
                journals.changesOf(j).forEach((field, v) -> {
                    if (v == null || v.isEmpty()) return;
                    first.putIfAbsent(field, v.get(0));
                    last.put(field, v.size() > 1 ? v.get(1) : null);
                });
            }
            var changed = new LinkedHashMap<String, String>();
            first.forEach((field, from) -> {
                if (Objects.equals(String.valueOf(from), String.valueOf(last.get(field)))) return; // changed and changed back
                var cf = CustomFields.idOf(field);
                if (cf != null) {
                    s.customField(cf).ifPresent(f -> changed.put("cf:" + cf, blank(customFields.text(s, f, from))));
                } else if (COLUMNS.containsKey(field)) {
                    changed.put(COLUMNS.get(field), journals.display(s, field, from));
                }
            });
            if (!changed.isEmpty()) out.put(wp.id(), new Dto.RowBaseline(false, changed));
        }
        return out;
    }

    private static String blank(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}

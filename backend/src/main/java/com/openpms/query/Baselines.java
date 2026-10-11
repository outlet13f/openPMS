package com.openpms.query;

import static com.openpms.db.Tables.JOURNALS;

import com.openpms.api.Dto;
import com.openpms.core.Ctx;
import com.openpms.customfield.CustomFields;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.wp.Journals;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * S06 기준선: how the listed work packages looked at a point in the past. The value a field had then is the "from" of its
 * first change after that point (journals keep [from, to] per field); an item made after it is new. Items that matched the
 * list then but no longer do are not shown.
 */
@Service
public class Baselines {
    /** Journal field → list column. */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("subject", "subject"), Map.entry("typeId", "type"), Map.entry("statusId", "status"), Map.entry("priorityId", "priority"),
            Map.entry("assigneeId", "assignee"), Map.entry("responsibleId", "responsible"), Map.entry("versionId", "version"),
            Map.entry("startDate", "startDate"), Map.entry("dueDate", "dueDate"), Map.entry("duration", "duration"), Map.entry("doneRatio", "doneRatio"),
            Map.entry("workHours", "work"), Map.entry("remainingHours", "remainingWork"), Map.entry("projectId", "project"));

    private final DSLContext db;
    private final Journals journals;
    private final CustomFields customFields;

    public Baselines(DSLContext db, Journals journals, CustomFields customFields) {
        this.db = db;
        this.journals = journals;
        this.customFields = customFields;
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

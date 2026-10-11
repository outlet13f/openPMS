package com.openpms.wp;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.customfield.CustomFields;
import com.openpms.notification.NotificationSettings;
import com.openpms.common.Dates;
import com.openpms.common.Html;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activity journals and the notifications they cause (architecture.md §3.6). A change is a map of
 * field → [before, after]; consecutive edits by the same person within the aggregation window merge.
 */
@Component
public class Journals {
    public static final String CREATED = "_created";
    /** Strongest first: one notification per person, with the best reason they have. */
    private static final List<String> REASON_RANK = List.of("mentioned", "assigned", "responsible", "watched", "created", "commented", "status_changed",
            "priority_changed", "dates_changed");

    private final DSLContext db;
    private final JsonMapper json;
    private final CustomFields customFields;
    private final NotificationSettings settings;

    public Journals(DSLContext db, JsonMapper json, CustomFields customFields, NotificationSettings settings) {
        this.db = db;
        this.json = json;
        this.customFields = customFields;
        this.settings = settings;
    }

    public Map<String, List<Object>> changesOf(JournalsRecord j) {
        return json.readValue(j.getChanges().data(), new TypeReference<LinkedHashMap<String, List<Object>>>() {});
    }

    public Map<String, Object> causeOf(JournalsRecord j) {
        return j.getCause() == null ? null : json.readValue(j.getCause().data(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** Write (or merge into the previous) journal entry. Returns the entry the change ended up in. */
    public JournalsRecord write(Ctx ctx, WorkPackagesRecord wp, Map<String, List<Object>> changes, String notesHtml, Map<String, Object> cause,
                                boolean internal) {
        var now = OffsetDateTime.now();
        var last = db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wp.getId())).orderBy(JOURNALS.VERSION.desc()).limit(1).fetchOne();
        var window = Duration.ofMinutes(ctx.s().settings().journalAggregationMinutes());
        boolean merge = last != null && notesHtml.isEmpty() && last.getNotesHtml().isEmpty() && cause == null && last.getCause() == null
                && last.getUserId().equals(ctx.userId()) && !changesOf(last).containsKey(CREATED)
                && Duration.between(last.getCreatedAt(), now).compareTo(window) < 0;
        if (merge) {
            var merged = changesOf(last);
            changes.forEach((k, v) -> {
                var from = merged.containsKey(k) ? merged.get(k).get(0) : v.get(0);
                if (Objects.equals(String.valueOf(from), String.valueOf(v.get(1)))) merged.remove(k);
                else merged.put(k, listOf(from, v.get(1)));
            });
            last.setChanges(JSONB.valueOf(json.writeValueAsString(merged)));
            last.setUpdatedAt(now);
            last.store();
            return last;
        }
        var j = db.newRecord(JOURNALS);
        j.setWorkPackageId(wp.getId());
        j.setVersion(last == null ? 1 : last.getVersion() + 1);
        j.setUserId(ctx.userId());
        j.setNotesHtml(notesHtml);
        j.setNotesText(Html.text(notesHtml));
        j.setIsInternal(internal);
        j.setChanges(JSONB.valueOf(json.writeValueAsString(changes)));
        j.setCause(cause == null ? null : JSONB.valueOf(json.writeValueAsString(cause)));
        j.setCreatedAt(now);
        j.setUpdatedAt(now);
        j.store();
        return j;
    }

    public static List<Object> listOf(Object a, Object b) {
        var l = new ArrayList<>(2);
        l.add(a);
        l.add(b);
        return l;
    }

    /**
     * Who hears about a journal (architecture.md §3.6), within each person's notification settings (S20): mentions and
     * watching always; assignee and responsible when switched on; and, for anyone in the project who asked for them,
     * new items, comments and status / priority / date changes. One notification per person, strongest reason.
     */
    public void fanOut(Ctx ctx, WorkPackagesRecord wp, JournalsRecord journal, List<UUID> mentioned) {
        var s = ctx.s();
        var project = s.project(wp.getProjectId()).orElseThrow();
        var recipients = new LinkedHashMap<UUID, String>();
        java.util.function.BiConsumer<UUID, String> offer = (userId, reason) -> {
            if (userId == null || userId.equals(ctx.userId())) return;
            var u = s.user(userId).filter(x -> "active".equals(x.status())).orElse(null);
            if (u == null || !ctx.can(u, project, wp.getId(), "wp.view")) return; // never notify people who cannot see the item
            if (journal.getIsInternal() && !ctx.can(u, project, "wp.view_internal_comments")) return;
            var cur = recipients.get(userId);
            if (cur == null || REASON_RANK.indexOf(reason) < REASON_RANK.indexOf(cur)) recipients.put(userId, reason);
        };
        var changes = changesOf(journal);
        // the non-participating events this journal is about, in rank order
        var events = new ArrayList<String>();
        if (changes.containsKey(CREATED)) events.add("created");
        if (!journal.getNotesHtml().isEmpty()) events.add("commented");
        if (changes.containsKey("statusId")) events.add("status_changed");
        if (changes.containsKey("priorityId")) events.add("priority_changed");
        if (changes.containsKey("startDate") || changes.containsKey("dueDate")) events.add("dates_changed");
        var people = new java.util.HashSet<UUID>();
        if (wp.getAssigneeId() != null) people.add(wp.getAssigneeId());
        if (wp.getResponsibleId() != null) people.add(wp.getResponsibleId());
        var members = events.isEmpty() ? List.<UUID>of() : s.projectUsers(project.id()).stream().map(Snapshot.UserRow::id).toList();
        people.addAll(members);
        var effective = settings.effective(project.id(), people);

        mentioned.forEach(id -> offer.accept(id, "mentioned"));
        if (wp.getAssigneeId() != null && effective.get(wp.getAssigneeId()).assignee()) offer.accept(wp.getAssigneeId(), "assigned");
        if (wp.getResponsibleId() != null && effective.get(wp.getResponsibleId()).responsible()) offer.accept(wp.getResponsibleId(), "responsible");
        db.select(WATCHERS.USER_ID).from(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.eq(wp.getId())).fetch(WATCHERS.USER_ID).forEach(id -> offer.accept(id, "watched"));
        for (var userId : members) {
            var st = effective.get(userId);
            events.stream().filter(e -> switch (e) {
                case "created" -> st.workPackageCreated();
                case "commented" -> st.commented();
                case "status_changed" -> st.statusChanged();
                case "priority_changed" -> st.priorityChanged();
                default -> st.datesChanged();
            }).findFirst().ifPresent(e -> offer.accept(userId, e));
        }
        recipients.forEach((recipientId, reason) -> {
            int touched = db.update(NOTIFICATIONS).set(NOTIFICATIONS.CREATED_AT, journal.getUpdatedAt())
                    .where(NOTIFICATIONS.JOURNAL_ID.eq(journal.getId()), NOTIFICATIONS.RECIPIENT_ID.eq(recipientId), NOTIFICATIONS.READ_AT.isNull()).execute();
            if (touched > 0) return;
            db.insertInto(NOTIFICATIONS)
                    .set(NOTIFICATIONS.RECIPIENT_ID, recipientId).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp.getId()).set(NOTIFICATIONS.PROJECT_ID, wp.getProjectId())
                    .set(NOTIFICATIONS.JOURNAL_ID, journal.getId()).set(NOTIFICATIONS.ACTOR_ID, ctx.userId()).set(NOTIFICATIONS.REASON, reason)
                    .set(NOTIFICATIONS.CREATED_AT, journal.getUpdatedAt())
                    .execute();
        });
    }

    /**
     * The one notification a share sends: reason "shared", no journal. Off when the person turned "shared" off in
     * their notification settings (the project row wins over the personal default; no row means on).
     */
    public void notifyShared(Ctx ctx, WorkPackagesRecord wp, UUID recipientId) {
        if (!settings.effective(wp.getProjectId(), recipientId).shared()) return;
        db.insertInto(NOTIFICATIONS)
                .set(NOTIFICATIONS.RECIPIENT_ID, recipientId).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp.getId()).set(NOTIFICATIONS.PROJECT_ID, wp.getProjectId())
                .set(NOTIFICATIONS.ACTOR_ID, ctx.userId()).set(NOTIFICATIONS.REASON, "shared")
                .execute();
    }

    /* ---------- DTOs ---------- */

    private static final Map<String, String> LABEL = Map.ofEntries(
            Map.entry("subject", "제목"), Map.entry("projectId", "프로젝트"), Map.entry("descriptionHtml", "설명"), Map.entry("typeId", "유형"), Map.entry("statusId", "상태"),
            Map.entry("priorityId", "우선순위"), Map.entry("assigneeId", "담당자"), Map.entry("responsibleId", "책임자"), Map.entry("versionId", "버전"),
            Map.entry("categoryId", "카테고리"), Map.entry("parentId", "상위 작업"), Map.entry("startDate", "시작일"), Map.entry("dueDate", "완료일"),
            Map.entry("duration", "기간"), Map.entry("scheduleManually", "일정 방식"), Map.entry("ignoreNonWorkingDays", "근무일만 계산"),
            Map.entry("workHours", "작업량"), Map.entry("remainingHours", "남은 작업"), Map.entry("doneRatio", "완료 %"),
            Map.entry("attachment", "첨부 파일"));

    private static final List<String> ORDER = List.of("projectId", "subject", "descriptionHtml", "typeId", "statusId", "priorityId", "assigneeId", "responsibleId",
            "versionId", "categoryId", "parentId", "startDate", "dueDate", "duration", "scheduleManually", "ignoreNonWorkingDays", "workHours",
            "remainingHours", "doneRatio", "attachment");

    /** "attachment:<id>" → "attachment" (files are keyed one by one). */
    private static String base(String field) {
        int i = field.indexOf(':');
        return i < 0 ? field : field.substring(0, i);
    }

    private static UUID uuid(Object v) {
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trimNumber(Object v) {
        var s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public String display(Snapshot s, String field, Object v) {
        if (v == null || "".equals(v)) return null;
        return switch (field) {
            case "statusId" -> s.status(uuid(v)).map(Snapshot.StatusRow::name).orElse(null);
            case "typeId" -> s.type(uuid(v)).map(Snapshot.TypeRow::name).orElse(null);
            case "projectId" -> s.project(uuid(v)).map(Snapshot.ProjectRow::name).orElse("삭제된 프로젝트");
            case "priorityId" -> s.priority(uuid(v)).map(Snapshot.PriorityRow::name).orElse(null);
            case "assigneeId", "responsibleId" -> Refs.user(s, uuid(v)).name();
            case "versionId" -> s.version(uuid(v)).map(Snapshot.VersionRow::name).orElse(null);
            case "categoryId" -> s.category(uuid(v)).map(Snapshot.CategoryRow::name).orElse(null);
            case "parentId" -> {
                var p = db.select(WORK_PACKAGES.NUMBER, WORK_PACKAGES.SUBJECT).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(uuid(v))).fetchOne();
                yield p == null ? null : "#" + p.value1() + " " + p.value2();
            }
            case "startDate", "dueDate" -> Dates.format(LocalDate.parse(String.valueOf(v)));
            case "duration" -> trimNumber(v) + "일";
            case "scheduleManually" -> Boolean.TRUE.equals(v) ? "수동" : "자동";
            case "ignoreNonWorkingDays" -> Boolean.TRUE.equals(v) ? "아니요" : "예";
            case "workHours", "remainingHours" -> trimNumber(v) + "시간";
            case "doneRatio" -> trimNumber(v) + "%";
            case "descriptionHtml" -> null;
            default -> String.valueOf(v);
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    public Dto.Journal toDto(Ctx ctx, JournalsRecord j, Snapshot.ProjectRow project) {
        var s = ctx.s();
        var changes = changesOf(j);
        var details = new ArrayList<Dto.JournalDetail>();
        if (changes.containsKey(CREATED)) details.add(new Dto.JournalDetail(CREATED, "작업을 만들었습니다", null, null));
        // jsonb does not keep key order: show fields in the form's order (start before due …), custom fields after, in field order
        java.util.function.ToIntFunction<String> rank = f -> {
            var cf = CustomFields.idOf(f);
            if (cf != null) return ORDER.size() + 1 + s.customField(cf).map(Snapshot.CustomFieldRow::position).orElse(0);
            return ORDER.indexOf(base(f)) < 0 ? ORDER.size() : ORDER.indexOf(base(f));
        };
        changes.keySet().stream().filter(f -> !f.equals(CREATED) && !f.equals("lockVersion"))
                .sorted(java.util.Comparator.comparingInt(rank))
                .forEach(field -> {
                    var v = changes.get(field);
                    var from = v.get(0);
                    var to = v.size() > 1 ? v.get(1) : null;
                    var cfId = CustomFields.idOf(field);
                    if (cfId != null) {
                        // a custom field: its current name and display form; a deleted field's history is not shown
                        s.customField(cfId).ifPresent(f -> details.add(new Dto.JournalDetail(field, f.name(), blankToNull(customFields.text(s, f, from)),
                                blankToNull(customFields.text(s, f, to)))));
                        return;
                    }
                    details.add(new Dto.JournalDetail(field, LABEL.getOrDefault(base(field), field), display(s, base(field), from), display(s, base(field), to)));
                });
        boolean canEdit = !j.getNotesHtml().isEmpty()
                && ((j.getUserId().equals(ctx.userId()) && ctx.can(project, j.getWorkPackageId(), "wp.edit_own_comments")) || ctx.can(project, "wp.edit_comments"));
        return new Dto.Journal(j.getId(), j.getVersion(), Refs.user(s, j.getUserId()), j.getNotesHtml(), j.getIsInternal(), details, causeOf(j),
                j.getCreatedAt(), j.getUpdatedAt(), canEdit);
    }
}

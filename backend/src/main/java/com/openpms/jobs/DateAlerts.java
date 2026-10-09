package com.openpms.jobs;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.NOTIFICATION_SETTINGS;
import static com.openpms.db.Tables.USER_PREFERENCES;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Hourly date alerts (architecture.md §3.6, §4 date-alerts): for open work packages a person is involved in
 * (assignee, responsible or watcher), "starts in N days", "due in N days" and "overdue", counted in that person's
 * time zone. The unique index notifications_one_date_alert makes every run idempotent: one alert per person, item,
 * kind and date. Moving a date gives a new alert for the new date.
 */
@Component
public class DateAlerts {
    /** Used when someone has no settings row; the same as the column defaults (V4). */
    public static final Setting DEFAULTS = new Setting(1, 1, true);

    /** null days = off. */
    public record Setting(Integer startDays, Integer dueDays, boolean overdue) {}

    private final DSLContext db;
    private final Current current;

    public DateAlerts(DSLContext db, Current current) {
        this.db = db;
        this.current = current;
    }

    /** @return the number of new alerts */
    public int run(Instant now) {
        var s = current.snapshot();
        var userDefaults = new HashMap<UUID, Setting>();
        var projectSettings = new HashMap<List<UUID>, Setting>();
        db.selectFrom(NOTIFICATION_SETTINGS).fetch().forEach(r -> {
            var st = new Setting(r.getStartDateAlertDays() == null ? null : r.getStartDateAlertDays().intValue(),
                    r.getDueDateAlertDays() == null ? null : r.getDueDateAlertDays().intValue(), r.getOverdueAlert());
            if (r.getProjectId() == null) userDefaults.put(r.getUserId(), st);
            else projectSettings.put(List.of(r.getUserId(), r.getProjectId()), st);
        });
        var pausedUntil = new HashMap<UUID, LocalDate>();
        db.select(USER_PREFERENCES.USER_ID, USER_PREFERENCES.REMINDERS_PAUSED_UNTIL).from(USER_PREFERENCES)
                .where(USER_PREFERENCES.REMINDERS_PAUSED_UNTIL.isNotNull()).fetch().forEach(r -> pausedUntil.put(r.value1(), r.value2()));

        // every zone's "today" lies within a day of UTC's, and alerts look at most 7 days ahead
        var utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        var open = s.statuses().stream().filter(x -> !x.isClosed()).map(Snapshot.StatusRow::id).toList();
        var wps = db.selectFrom(WORK_PACKAGES)
                .where(WORK_PACKAGES.STATUS_ID.in(open))
                .and(WORK_PACKAGES.START_DATE.between(utcToday.minusDays(1), utcToday.plusDays(8)).or(WORK_PACKAGES.DUE_DATE.le(utcToday.plusDays(8))))
                .fetch();
        if (wps.isEmpty()) return 0;
        var watchers = new HashMap<UUID, List<UUID>>();
        db.select(WATCHERS.WORK_PACKAGE_ID, WATCHERS.USER_ID).from(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.in(wps.stream().map(WorkPackagesRecord::getId).toList()))
                .fetch().forEach(r -> watchers.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));

        var ctxs = new HashMap<UUID, Ctx>();
        var created = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        int added = 0;
        for (var wp : wps) {
            var project = s.project(wp.getProjectId()).orElse(null);
            if (project == null || project.archivedAt() != null) continue;
            boolean milestone = s.type(wp.getTypeId()).map(Snapshot.TypeRow::isMilestone).orElse(false);
            var people = new LinkedHashSet<UUID>();
            if (wp.getAssigneeId() != null) people.add(wp.getAssigneeId());
            if (wp.getResponsibleId() != null) people.add(wp.getResponsibleId());
            people.addAll(watchers.getOrDefault(wp.getId(), List.of()));
            for (var userId : people) {
                var u = s.user(userId).filter(x -> "active".equals(x.status())).orElse(null);
                if (u == null) continue;
                var ctx = ctxs.computeIfAbsent(userId, id -> new Ctx(id, () -> s));
                if (!ctx.can(project, wp.getId(), "wp.view")) continue;
                var today = LocalDate.ofInstant(now, zone(u.timeZone()));
                var paused = pausedUntil.get(userId);
                if (paused != null && !today.isAfter(paused)) continue;
                var st = projectSettings.getOrDefault(List.of(userId, project.id()), userDefaults.getOrDefault(userId, DEFAULTS));
                var start = wp.getStartDate();
                var due = wp.getDueDate();
                // a milestone has one date: it gets the due-date alert only
                if (!milestone && st.startDays() != null && start != null && start.equals(today.plusDays(st.startDays())))
                    added += insert(userId, wp, "date_alert_start", start, created);
                if (st.dueDays() != null && due != null && due.equals(today.plusDays(st.dueDays())))
                    added += insert(userId, wp, "date_alert_due", due, created);
                if (st.overdue() && due != null && due.isBefore(today))
                    added += insert(userId, wp, "date_alert_overdue", due, created);
            }
        }
        return added;
    }

    private int insert(UUID userId, WorkPackagesRecord wp, String reason, LocalDate date, OffsetDateTime at) {
        return db.insertInto(NOTIFICATIONS)
                .set(NOTIFICATIONS.RECIPIENT_ID, userId).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp.getId()).set(NOTIFICATIONS.PROJECT_ID, wp.getProjectId())
                .set(NOTIFICATIONS.REASON, reason).set(NOTIFICATIONS.ALERT_DATE, date).set(NOTIFICATIONS.CREATED_AT, at).set(NOTIFICATIONS.UPDATED_AT, at)
                .onConflictDoNothing()
                .execute();
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.of("Asia/Seoul");
        }
    }
}

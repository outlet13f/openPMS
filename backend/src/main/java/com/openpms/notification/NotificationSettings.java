package com.openpms.notification;

import static com.openpms.db.Tables.NOTIFICATION_SETTINGS;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.db.tables.records.NotificationSettingsRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Notification settings (S20): a person's default row (project_id null) and per-project rows; a project row wins.
 * No row at all means DEFAULT, the same as the column defaults. frontend/src/api/fake/notification-settings.ts
 * holds the same rules.
 */
@Component
public class NotificationSettings {
    public static final Dto.NotificationSetting DEFAULT = new Dto.NotificationSetting(true, true, true, false, false, false, false, false, 1, 1, false);
    private static final List<Integer> DAYS = List.of(0, 1, 3, 7);

    private final DSLContext db;

    public NotificationSettings(DSLContext db) {
        this.db = db;
    }

    public static Dto.NotificationSetting dto(NotificationSettingsRecord r) {
        return new Dto.NotificationSetting(r.getAssignee(), r.getResponsible(), r.getShared(), r.getWorkPackageCreated(), r.getCommented(),
                r.getStatusChanged(), r.getPriorityChanged(), r.getDatesChanged(),
                r.getStartDateAlertDays() == null ? null : r.getStartDateAlertDays().intValue(),
                r.getDueDateAlertDays() == null ? null : r.getDueDateAlertDays().intValue(), r.getOverdueAlert());
    }

    /** The setting each of these people uses in a project. */
    public Map<UUID, Dto.NotificationSetting> effective(UUID projectId, Collection<UUID> userIds) {
        var out = new HashMap<UUID, Dto.NotificationSetting>();
        if (userIds.isEmpty()) return out;
        var rows = db.selectFrom(NOTIFICATION_SETTINGS)
                .where(NOTIFICATION_SETTINGS.USER_ID.in(userIds), NOTIFICATION_SETTINGS.PROJECT_ID.isNull().or(NOTIFICATION_SETTINGS.PROJECT_ID.eq(projectId)))
                .fetch();
        for (var r : rows) if (r.getProjectId() == null) out.put(r.getUserId(), dto(r));
        for (var r : rows) if (r.getProjectId() != null) out.put(r.getUserId(), dto(r));
        for (var u : userIds) out.putIfAbsent(u, DEFAULT);
        return out;
    }

    public Dto.NotificationSetting effective(UUID projectId, UUID userId) {
        return effective(projectId, List.of(userId)).get(userId);
    }

    private static boolean flag(Map<String, Object> in, String key, boolean fallback) {
        if (!In.has(in, key)) return fallback;
        if (!(in.get(key) instanceof Boolean b)) throw ApiException.field(key, "켜기 또는 끄기로 보내세요.");
        return b;
    }

    private static Integer days(Map<String, Object> in, String key, Integer fallback) {
        if (!In.has(in, key)) return fallback;
        var v = in.get(key);
        if (v == null) return null;
        if (!(v instanceof Number n) || !DAYS.contains(n.intValue()) || n.doubleValue() != n.intValue())
            throw ApiException.field(key, "알림 시점은 당일, 1일·3일·7일 전 중에서 고르세요.");
        return n.intValue();
    }

    /** A request body → a checked setting (missing keys take the defaults). */
    public static Dto.NotificationSetting parse(Map<String, Object> in) {
        var d = DEFAULT;
        return new Dto.NotificationSetting(flag(in, "assignee", d.assignee()), flag(in, "responsible", d.responsible()), flag(in, "shared", d.shared()),
                flag(in, "workPackageCreated", d.workPackageCreated()), flag(in, "commented", d.commented()), flag(in, "statusChanged", d.statusChanged()),
                flag(in, "priorityChanged", d.priorityChanged()), flag(in, "datesChanged", d.datesChanged()),
                days(in, "startDateAlertDays", d.startDateAlertDays()), days(in, "dueDateAlertDays", d.dueDateAlertDays()),
                flag(in, "overdueAlert", d.overdueAlert()));
    }

    /** Creates or replaces one row (projectId null = the default). */
    public void save(UUID userId, UUID projectId, Dto.NotificationSetting s) {
        var where = NOTIFICATION_SETTINGS.USER_ID.eq(userId).and(projectId == null ? NOTIFICATION_SETTINGS.PROJECT_ID.isNull() : NOTIFICATION_SETTINGS.PROJECT_ID.eq(projectId));
        var r = db.selectFrom(NOTIFICATION_SETTINGS).where(where).fetchOne();
        if (r == null) {
            r = db.newRecord(NOTIFICATION_SETTINGS);
            r.setUserId(userId);
            r.setProjectId(projectId);
        }
        r.setAssignee(s.assignee());
        r.setResponsible(s.responsible());
        r.setShared(s.shared());
        r.setWorkPackageCreated(s.workPackageCreated());
        r.setCommented(s.commented());
        r.setStatusChanged(s.statusChanged());
        r.setPriorityChanged(s.priorityChanged());
        r.setDatesChanged(s.datesChanged());
        r.setStartDateAlertDays(s.startDateAlertDays() == null ? null : s.startDateAlertDays().shortValue());
        r.setDueDateAlertDays(s.dueDateAlertDays() == null ? null : s.dueDateAlertDays().shortValue());
        r.setOverdueAlert(s.overdueAlert());
        r.store();
    }
}

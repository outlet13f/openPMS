package com.openpms.notification;

import static com.openpms.db.Tables.NOTIFICATION_SETTINGS;
import static com.openpms.db.Tables.USER_PREFERENCES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.core.Text;
import com.openpms.db.tables.records.UserPreferencesRecord;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** My own settings (architecture.md §4.2 "/me"): preferences, including mail, and notification settings (S20). */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final Current current;
    private final DSLContext db;
    private final NotificationSettings settings;

    public AccountController(Current current, DSLContext db, NotificationSettings settings) {
        this.current = current;
        this.db = db;
        this.settings = settings;
    }

    /** Preferences as the API shows them; no row yet means the column defaults. */
    public static Dto.Preferences preferences(UserPreferencesRecord p) {
        if (p == null) return new Dto.Preferences(false, true, true, true, true, java.util.List.of("08:00"), null);
        return new Dto.Preferences(p.getCommentsNewestFirst(), p.getWarnOnUnsaved(), p.getKeyboardShortcuts(), p.getImmediateOnMention(),
                p.getDigestEnabled(), java.util.Arrays.stream(p.getDigestTimes()).sorted().map(t -> t.toString().substring(0, 5)).toList(),
                p.getRemindersPausedUntil());
    }

    @PutMapping("/preferences")
    @Transactional
    public Dto.Preferences updatePreferences(@RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var p = db.selectFrom(USER_PREFERENCES).where(USER_PREFERENCES.USER_ID.eq(ctx.userId())).fetchOne();
        if (p == null) {
            p = db.newRecord(USER_PREFERENCES);
            p.setUserId(ctx.userId());
        }
        for (var key : new String[] {"commentsNewestFirst", "warnOnUnsaved", "keyboardShortcuts", "immediateOnMention", "digestEnabled"}) {
            if (!In.has(body, key)) continue;
            if (!(body.get(key) instanceof Boolean b)) throw ApiException.field(key, "켜기 또는 끄기로 보내세요.");
            switch (key) {
                case "commentsNewestFirst" -> p.setCommentsNewestFirst(b);
                case "warnOnUnsaved" -> p.setWarnOnUnsaved(b);
                case "keyboardShortcuts" -> p.setKeyboardShortcuts(b);
                case "immediateOnMention" -> p.setImmediateOnMention(b);
                default -> p.setDigestEnabled(b);
            }
        }
        if (In.has(body, "digestTimes")) {
            if (!(body.get("digestTimes") instanceof Collection<?> c) || c.isEmpty() || c.size() > 4)
                throw ApiException.field("digestTimes", "요약 메일 시각을 1~4개 정하세요.");
            var times = new ArrayList<LocalTime>();
            for (var x : c) {
                if (!(x instanceof String str) || !TIME.matcher(str).matches()) throw ApiException.field("digestTimes", "시각은 HH:MM 형식으로 입력하세요.");
                times.add(LocalTime.parse(str));
            }
            if (new HashSet<>(times).size() != times.size()) throw ApiException.field("digestTimes", "같은 시각이 두 번 있습니다.");
            times.sort(null);
            p.setDigestTimes(times.toArray(LocalTime[]::new));
        }
        if (In.has(body, "remindersPausedUntil")) {
            var v = body.get("remindersPausedUntil");
            try {
                p.setRemindersPausedUntil(v == null ? null : LocalDate.parse(String.valueOf(v)));
            } catch (RuntimeException e) {
                throw ApiException.field("remindersPausedUntil", "날짜를 YYYY-MM-DD 형식으로 입력하세요.");
            }
        }
        p.store();
        return preferences(p);
    }

    /* ---------- notification settings ---------- */

    @GetMapping("/notification-settings")
    public Dto.NotificationSettings notificationSettings() {
        var ctx = current.ctx();
        var s = ctx.s();
        var rows = db.selectFrom(NOTIFICATION_SETTINGS).where(NOTIFICATION_SETTINGS.USER_ID.eq(ctx.userId())).fetch();
        var def = rows.stream().filter(r -> r.getProjectId() == null).findFirst().map(NotificationSettings::dto).orElse(NotificationSettings.DEFAULT);
        var projects = rows.stream().filter(r -> r.getProjectId() != null)
                .map(r -> s.project(r.getProjectId()).filter(p -> ctx.can(p, "project.view")).map(p -> project(p, NotificationSettings.dto(r))).orElse(null))
                .filter(x -> x != null)
                .sorted((a, b) -> Text.KOREAN.compare(a.project().name(), b.project().name()))
                .toList();
        return new Dto.NotificationSettings(def, projects);
    }

    private static Dto.ProjectNotificationSetting project(Snapshot.ProjectRow p, Dto.NotificationSetting x) {
        return new Dto.ProjectNotificationSetting(Refs.project(p), x.assignee(), x.responsible(), x.shared(), x.workPackageCreated(), x.commented(),
                x.statusChanged(), x.priorityChanged(), x.datesChanged(), x.startDateAlertDays(), x.dueDateAlertDays(), x.overdueAlert());
    }

    @PutMapping("/notification-settings/default")
    @Transactional
    public Dto.NotificationSetting saveDefault(@RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var setting = NotificationSettings.parse(body);
        settings.save(ctx.userId(), null, setting);
        return setting;
    }

    @PutMapping("/notification-settings/projects/{project}")
    @Transactional
    public Dto.ProjectNotificationSetting saveProject(@PathVariable String project, @RequestBody Map<String, Object> body) {
        var ctx = current.ctx();
        var p = ctx.findProject(project);
        var setting = NotificationSettings.parse(body);
        settings.save(ctx.userId(), p.id(), setting);
        return project(p, setting);
    }

    @DeleteMapping("/notification-settings/projects/{project}")
    @Transactional
    public ResponseEntity<Void> removeProject(@PathVariable String project) {
        var ctx = current.ctx();
        var p = ctx.findProject(project);
        db.deleteFrom(NOTIFICATION_SETTINGS).where(NOTIFICATION_SETTINGS.USER_ID.eq(ctx.userId()), NOTIFICATION_SETTINGS.PROJECT_ID.eq(p.id())).execute();
        return ResponseEntity.noContent().build();
    }
}

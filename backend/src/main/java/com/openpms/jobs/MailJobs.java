package com.openpms.jobs;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.USER_PREFERENCES;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.common.Mailer;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.db.tables.records.NotificationsRecord;
import com.openpms.db.tables.records.UserPreferencesRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import com.openpms.notification.NotificationTexts;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Notification mail (architecture.md §4 mail-mention, mail-digest). Mail goes out at least once: emailed_at is set
 * after the relay accepted it, so a crash in between can repeat a mail, which is accepted. Read notifications are
 * never mailed. Bodies are plain text written here; nothing about them is logged.
 */
@Component
public class MailJobs {
    /** A mention waits this long, so a comment fixed right after posting is mailed once, as fixed. */
    static final Duration MENTION_DELAY = Duration.ofMinutes(1);
    /** Older unsent notifications are left out of mail (e.g. after a long outage, or the first run). */
    static final Duration MAX_AGE = Duration.ofDays(7);
    private static final LocalTime DEFAULT_DIGEST_TIME = LocalTime.of(8, 0);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM.dd HH:mm");

    private final DSLContext db;
    private final Current current;
    private final Mailer mailer;
    private final NotificationTexts texts;

    public MailJobs(DSLContext db, Current current, Mailer mailer, NotificationTexts texts) {
        this.db = db;
        this.current = current;
        this.mailer = mailer;
        this.texts = texts;
    }

    /** Facts a mail needs about each notification, loaded in a few queries. */
    private record Loaded(Snapshot s, Map<UUID, WorkPackagesRecord> wps, Map<UUID, JournalsRecord> journals) {}

    private Loaded load(List<NotificationsRecord> items) {
        var s = current.snapshot();
        var wps = new HashMap<UUID, WorkPackagesRecord>();
        var wpIds = items.stream().map(NotificationsRecord::getWorkPackageId).distinct().toList();
        if (!wpIds.isEmpty()) db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(wpIds)).fetch().forEach(w -> wps.put(w.getId(), w));
        var js = new HashMap<UUID, JournalsRecord>();
        var jIds = items.stream().map(NotificationsRecord::getJournalId).filter(x -> x != null).distinct().toList();
        if (!jIds.isEmpty()) db.selectFrom(JOURNALS).where(JOURNALS.ID.in(jIds)).fetch().forEach(j -> js.put(j.getId(), j));
        return new Loaded(s, wps, js);
    }

    private Map<UUID, UserPreferencesRecord> preferences() {
        return db.selectFrom(USER_PREFERENCES).fetch().stream().collect(Collectors.toMap(UserPreferencesRecord::getUserId, r -> r));
    }

    private void markEmailed(List<UUID> ids, Instant now) {
        if (!ids.isEmpty()) db.update(NOTIFICATIONS).set(NOTIFICATIONS.EMAILED_AT, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).where(NOTIFICATIONS.ID.in(ids)).execute();
    }

    private String link(Snapshot s, WorkPackagesRecord wp) {
        var p = s.project(wp.getProjectId()).map(Snapshot.ProjectRow::identifier).orElse("");
        return mailer.link("/projects/" + p + "/work-packages/" + wp.getNumber() + "/activity");
    }

    private static String title(WorkPackagesRecord wp) {
        return "#" + wp.getNumber() + " " + wp.getSubject();
    }

    /* ---------- mentions, right away ---------- */

    /** @return mails sent */
    public int mentions(Instant now) {
        var items = db.selectFrom(NOTIFICATIONS)
                .where(NOTIFICATIONS.REASON.eq("mentioned"), NOTIFICATIONS.EMAILED_AT.isNull(), NOTIFICATIONS.READ_AT.isNull(),
                        NOTIFICATIONS.CREATED_AT.le(OffsetDateTime.ofInstant(now.minus(MENTION_DELAY), ZoneOffset.UTC)),
                        NOTIFICATIONS.CREATED_AT.ge(OffsetDateTime.ofInstant(now.minus(MAX_AGE), ZoneOffset.UTC)))
                .orderBy(NOTIFICATIONS.CREATED_AT)
                .fetch();
        if (items.isEmpty()) return 0;
        var prefs = preferences();
        var l = load(items);
        int sent = 0;
        var drop = new ArrayList<UUID>();
        for (var n : items) {
            var pref = prefs.get(n.getRecipientId());
            if (pref != null && !pref.getImmediateOnMention()) continue; // goes into the digest
            var u = l.s().user(n.getRecipientId()).filter(x -> "active".equals(x.status())).orElse(null);
            var wp = l.wps().get(n.getWorkPackageId());
            var project = wp == null ? null : l.s().project(wp.getProjectId()).orElse(null);
            if (u == null || project == null || !new Ctx(u.id(), l::s).can(project, "wp.view")) {
                drop.add(n.getId()); // nobody to tell any more
                continue;
            }
            var actor = n.getActorId() == null ? "누군가" : Refs.user(l.s(), n.getActorId()).name();
            var j = n.getJournalId() == null ? null : l.journals().get(n.getJournalId());
            var said = j == null ? "" : j.getNotesText();
            if (said.length() > 500) said = said.substring(0, 500) + "…";
            var body = new StringBuilder()
                    .append(actor).append("님이 ").append(project.name()).append(" · ").append(title(wp)).append("에서 나를 언급했습니다.\n\n");
            if (!said.isBlank()) body.append("  \"").append(said).append("\"\n\n");
            body.append("작업 열기: ").append(link(l.s(), wp)).append('\n');
            if (mailer.send(u.email(), "[openPMS] " + actor + "님이 " + title(wp) + "에서 나를 언급했습니다", body.toString())) {
                markEmailed(List.of(n.getId()), now);
                sent++;
            }
        }
        markEmailed(drop, now);
        return sent;
    }

    /* ---------- the daily digest ---------- */

    /**
     * The latest digest time at or before {@code now} in the person's zone (today's, or yesterday's last one).
     * Package-private for tests.
     */
    static ZonedDateTime latestSlot(Instant now, String timeZone, List<LocalTime> times) {
        var local = ZonedDateTime.ofInstant(now, DateAlerts.zone(timeZone));
        var sorted = times.isEmpty() ? List.of(DEFAULT_DIGEST_TIME) : times.stream().sorted().toList();
        ZonedDateTime best = null;
        for (var t : sorted) {
            var at = local.toLocalDate().atTime(t).atZone(local.getZone());
            if (!at.isAfter(local)) best = at;
        }
        return best != null ? best : local.toLocalDate().minusDays(1).atTime(sorted.getLast()).atZone(local.getZone());
    }

    /** @return digests sent */
    public int digests(Instant now) {
        var s = current.snapshot();
        var prefs = preferences();
        var pending = db.selectFrom(NOTIFICATIONS)
                .where(NOTIFICATIONS.EMAILED_AT.isNull(), NOTIFICATIONS.READ_AT.isNull(),
                        NOTIFICATIONS.CREATED_AT.le(OffsetDateTime.ofInstant(now, ZoneOffset.UTC)),
                        NOTIFICATIONS.CREATED_AT.ge(OffsetDateTime.ofInstant(now.minus(MAX_AGE), ZoneOffset.UTC)))
                .orderBy(NOTIFICATIONS.CREATED_AT.desc())
                .fetch().stream().collect(Collectors.groupingBy(NotificationsRecord::getRecipientId, LinkedHashMap::new, Collectors.toList()));
        int sent = 0;
        for (var u : s.users().values()) {
            if (!"active".equals(u.status())) continue;
            var pref = prefs.get(u.id());
            if (pref != null && !pref.getDigestEnabled()) continue;
            var times = pref == null || pref.getDigestTimes() == null ? List.<LocalTime>of() : Arrays.asList(pref.getDigestTimes());
            var slot = latestSlot(now, u.timeZone(), times);
            var last = pref == null ? null : pref.getLastDigestAt();
            if (last != null && !last.toInstant().isBefore(slot.toInstant())) continue; // this slot is done
            var mine = pending.getOrDefault(u.id(), List.of());
            boolean ok = true;
            if (!mine.isEmpty()) {
                var ctx = new Ctx(u.id(), () -> s);
                var loaded = load(mine);
                var visible = new ArrayList<NotificationsRecord>();
                for (var n : mine) {
                    var wp = loaded.wps().get(n.getWorkPackageId());
                    var project = wp == null ? null : s.project(wp.getProjectId()).orElse(null);
                    if (project != null && ctx.can(project, "wp.view")) visible.add(n);
                }
                if (!visible.isEmpty()) {
                    ok = mailer.send(u.email(), "[openPMS] 읽지 않은 알림 " + visible.size() + "건", digestBody(ctx, u, visible, loaded, times));
                    if (ok) {
                        sent++;
                        markEmailed(visible.stream().map(NotificationsRecord::getId).toList(), now);
                    }
                }
            }
            if (ok) recordDigest(u.id(), now);
        }
        return sent;
    }

    private String digestBody(Ctx ctx, Snapshot.UserRow u, List<NotificationsRecord> items, Loaded l, List<LocalTime> times) {
        var zone = DateAlerts.zone(u.timeZone());
        var b = new StringBuilder().append(u.name()).append("님, 아직 확인하지 않은 알림이 ").append(items.size()).append("건 있습니다.\n");
        var byWp = items.stream().collect(Collectors.groupingBy(NotificationsRecord::getWorkPackageId, LinkedHashMap::new, Collectors.toList()));
        byWp.forEach((wpId, ns) -> {
            var wp = l.wps().get(wpId);
            var project = l.s().project(wp.getProjectId()).orElseThrow();
            b.append("\n■ ").append(project.name()).append(" · ").append(title(wp)).append('\n');
            for (var n : ns) {
                var j = n.getJournalId() == null ? null : l.journals().get(n.getJournalId());
                b.append("  - ").append(texts.summary(ctx, n, j, project))
                        .append(" (").append(WHEN.format(n.getCreatedAt().atZoneSameInstant(zone))).append(")\n");
            }
            b.append("  ").append(link(l.s(), wp)).append('\n');
        });
        var at = (times.isEmpty() ? List.of(DEFAULT_DIGEST_TIME) : times).stream().sorted().map(LocalTime::toString).collect(Collectors.joining(", "));
        b.append("\n모든 알림: ").append(mailer.link("/notifications")).append("\n\n")
                .append("이 메일은 매일 ").append(at).append("(").append(zone.getId()).append(")에 읽지 않은 알림을 모아 보내는 요약입니다.\n");
        return b.toString();
    }

    private void recordDigest(UUID userId, Instant now) {
        var at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, userId).set(USER_PREFERENCES.LAST_DIGEST_AT, at)
                .onConflict(USER_PREFERENCES.USER_ID).doUpdate().set(USER_PREFERENCES.LAST_DIGEST_AT, at)
                .execute();
    }
}

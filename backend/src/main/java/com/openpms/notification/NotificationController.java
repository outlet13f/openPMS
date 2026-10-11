package com.openpms.notification;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.db.tables.records.NotificationsRecord;
import com.openpms.wp.Assembler;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The inbox (F09): notifications grouped by work package, newest first. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final Current current;
    private final DSLContext db;
    private final NotificationTexts texts;

    public NotificationController(Current current, DSLContext db, NotificationTexts texts) {
        this.current = current;
        this.db = db;
        this.texts = texts;
    }

    /** The newest this many matching notifications are listed (the filters apply first, so older matches are still reachable). */
    static final int LIMIT = 500;

    /** Mine, narrowed by reasons (comma-separated, empty: all) and project (identifier or id, empty: all). */
    private org.jooq.Condition mine(com.openpms.core.Ctx ctx, String reasons, String project) {
        var cond = NOTIFICATIONS.RECIPIENT_ID.eq(ctx.userId());
        var rs = reasons == null ? List.<String>of() : java.util.Arrays.stream(reasons.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (!rs.isEmpty()) cond = cond.and(NOTIFICATIONS.REASON.in(rs));
        if (project != null && !project.isBlank()) {
            var p = ctx.s().projectByIdOrIdentifier(project.trim()).orElse(null);
            cond = cond.and(p == null ? org.jooq.impl.DSL.falseCondition()
                    : NOTIFICATIONS.PROJECT_ID.eq(p.id()));
        }
        return cond;
    }

    @GetMapping
    public List<Dto.NotificationGroup> list(@RequestParam(defaultValue = "unread") String filter, @RequestParam(required = false) String reasons,
                                            @RequestParam(required = false) String project) {
        var ctx = current.ctx();
        var s = ctx.s();
        var cond = mine(ctx, reasons, project);
        if (!"all".equals(filter)) cond = cond.and(NOTIFICATIONS.READ_AT.isNull());
        var items = db.selectFrom(NOTIFICATIONS).where(cond).orderBy(NOTIFICATIONS.CREATED_AT.desc()).limit(LIMIT).fetch();
        var wpIds = items.stream().map(NotificationsRecord::getWorkPackageId).distinct().toList();
        var wps = new HashMap<UUID, com.openpms.db.tables.records.WorkPackagesRecord>();
        if (!wpIds.isEmpty()) db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(wpIds)).fetch().forEach(w -> wps.put(w.getId(), w));
        var journalIds = items.stream().map(NotificationsRecord::getJournalId).filter(x -> x != null).distinct().toList();
        var js = new HashMap<UUID, JournalsRecord>();
        if (!journalIds.isEmpty()) db.selectFrom(JOURNALS).where(JOURNALS.ID.in(journalIds)).fetch().forEach(j -> js.put(j.getId(), j));

        record Group(Dto.WorkPackageRef wp, List<Dto.NotificationItem> items, int[] unread, OffsetDateTime[] latest) {}
        var groups = new LinkedHashMap<UUID, Group>();
        for (var n : items) {
            var wp = wps.get(n.getWorkPackageId());
            var home = wp == null ? null : s.project(wp.getProjectId()).orElse(null);
            if (wp == null || home == null || !ctx.can(home, wp.getId(), "wp.view")) continue;
            var g = groups.computeIfAbsent(wp.getId(), k -> new Group(Assembler.ref(s, wp), new ArrayList<>(), new int[] {0}, new OffsetDateTime[] {n.getCreatedAt()}));
            var j = n.getJournalId() == null ? null : js.get(n.getJournalId());
            g.items().add(new Dto.NotificationItem(n.getId(), n.getReason(), n.getReadAt() != null, Refs.user(s, n.getActorId()), texts.summary(ctx, n, j, home), n.getCreatedAt()));
            if (n.getReadAt() == null) g.unread()[0]++;
        }
        return groups.values().stream().map(g -> new Dto.NotificationGroup(g.wp(), g.items(), g.unread()[0], g.latest()[0])).toList();
    }

    public record Count(int count) {}

    public record ProjectCount(String identifier, String name, int unread) {}

    /**
     * Unread counts for the filter choices, over every notification (not just the listed ones): all and per reason within
     * the chosen project, per project within the chosen reasons. Items count when I may see their project's work packages.
     */
    public record Counts(int all, Map<String, Integer> byReason, List<ProjectCount> projects, int allProjects) {}

    @GetMapping("/counts")
    public Counts counts(@RequestParam(required = false) String reasons, @RequestParam(required = false) String project) {
        var ctx = current.ctx();
        var s = ctx.s();
        var rows = db.select(NOTIFICATIONS.REASON, NOTIFICATIONS.PROJECT_ID, org.jooq.impl.DSL.count()).from(NOTIFICATIONS)
                .where(NOTIFICATIONS.RECIPIENT_ID.eq(ctx.userId()), NOTIFICATIONS.READ_AT.isNull())
                .groupBy(NOTIFICATIONS.REASON, NOTIFICATIONS.PROJECT_ID).fetch();
        var rs = reasons == null ? java.util.Set.<String>of()
                : java.util.Arrays.stream(reasons.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toSet());
        var chosen = project == null || project.isBlank() ? null : s.projectByIdOrIdentifier(project.trim()).orElse(null);
        int all = 0, allProjects = 0;
        var byReason = new java.util.TreeMap<String, Integer>();
        var byProject = new LinkedHashMap<UUID, int[]>();
        for (var r : rows) {
            var p = s.project(r.value2()).orElse(null);
            if (p == null || !ctx.can(p, "wp.view")) continue;
            int n = r.value3();
            boolean inProject = chosen == null || chosen.id().equals(p.id());
            boolean inReasons = rs.isEmpty() || rs.contains(r.value1());
            if (inProject) {
                all += n;
                byReason.merge(r.value1(), n, Integer::sum);
            }
            if (inReasons) {
                allProjects += n;
                byProject.computeIfAbsent(p.id(), k -> new int[1])[0] += n;
            }
        }
        var projects = byProject.entrySet().stream().map(e -> s.project(e.getKey()).map(p -> new ProjectCount(p.identifier(), p.name(), e.getValue()[0])).orElseThrow())
                .sorted((a, b) -> com.openpms.core.Text.KOREAN.compare(a.name(), b.name())).toList();
        return new Counts(all, byReason, projects, allProjects);
    }

    @GetMapping("/unread-count")
    public Count unreadCount() {
        var ctx = current.ctx();
        return new Count(db.fetchCount(NOTIFICATIONS, NOTIFICATIONS.RECIPIENT_ID.eq(ctx.userId()).and(NOTIFICATIONS.READ_AT.isNull())));
    }

    @PostMapping("/read")
    @Transactional
    public ResponseEntity<Void> markRead(@RequestBody Map<String, Object> target) {
        var ctx = current.ctx();
        var cond = NOTIFICATIONS.RECIPIENT_ID.eq(ctx.userId()).and(NOTIFICATIONS.READ_AT.isNull());
        if (In.has(target, "all")) {
            // every unread notification
        } else if (In.has(target, "reasons") || In.has(target, "project")) {
            // what a filter matches, listed or not
            var rs = target.get("reasons") instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : null;
            cond = cond.and(mine(ctx, rs, In.str(target, "project")));
        } else if (In.has(target, "workPackageNumber")) {
            var n = In.integer(target, "workPackageNumber", "workPackageNumber");
            cond = cond.and(NOTIFICATIONS.WORK_PACKAGE_ID.in(db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n == null ? -1L : n.longValue()))));
        } else {
            var ids = In.uuids(target, "ids", "ids");
            cond = cond.and(ids.isEmpty() ? org.jooq.impl.DSL.falseCondition() : NOTIFICATIONS.ID.in(ids));
        }
        db.update(NOTIFICATIONS).set(NOTIFICATIONS.READ_AT, OffsetDateTime.now()).where(cond).execute();
        return ResponseEntity.noContent().build();
    }
}

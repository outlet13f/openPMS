package com.openpms.itsm;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.ServiceTicketsRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import com.openpms.webhook.Webhooks;
import com.openpms.wp.Assignees;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every minute: SLA deadlines that are close (20 % left) or past, announced once to the ticket's people (ITSM 1단계 §5). */
@Component
public class SlaJobs {
    private final DSLContext db;
    private final ServiceDeskConfig config;
    private final Assignees assignees;
    private final Webhooks webhooks;
    private final Current current;

    public SlaJobs(DSLContext db, ServiceDeskConfig config, Assignees assignees, Webhooks webhooks, Current current) {
        this.db = db;
        this.config = config;
        this.assignees = assignees;
        this.webhooks = webhooks;
        this.current = current;
    }

    /** @return the number of notifications sent */
    @Transactional
    public int run(Instant now) {
        var s = current.snapshot();
        int sent = 0;
        var rows = db.select().from(SERVICE_TICKETS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(SERVICE_TICKETS.WORK_PACKAGE_ID))
                .where(SERVICE_TICKETS.RESOLVED_AT.isNull(), SERVICE_TICKETS.PAUSED_SINCE.isNull(), SERVICE_TICKETS.SLA_POLICY_ID.isNotNull())
                .fetch();
        for (var r : rows) {
            var t = r.into(SERVICE_TICKETS);
            var w = r.into(WORK_PACKAGES);
            var p = s.project(w.getProjectId()).orElse(null);
            if (p == null || p.archivedAt() != null || !p.enabledModules().contains(ItsmIds.MODULE)) continue;
            var policy = config.policyFor(w.getProjectId(), w.getTypeId(), w.getPriorityId()).orElse(null);
            if (policy == null) continue;
            var holidays = s.calendar().holidays();
            if (t.getFirstRespondedAt() == null && t.getFirstResponseDueAt() != null) {
                // the update re-checks what the job read: a deadline an agent just moved, or a first response just given, is left alone
                Condition still = SERVICE_TICKETS.FIRST_RESPONSE_DUE_AT.eq(t.getFirstResponseDueAt()).and(SERVICE_TICKETS.FIRST_RESPONDED_AT.isNull());
                sent += check(s, p, w, t, now, "response", t.getFirstResponseDueAt(), policy.firstResponse(), policy, holidays,
                        SERVICE_TICKETS.RESPONSE_BREACHED, SERVICE_TICKETS.RESPONSE_WARNED, still);
            }
            if (t.getResolveDueAt() != null) {
                Condition still = SERVICE_TICKETS.RESOLVE_DUE_AT.eq(t.getResolveDueAt()).and(SERVICE_TICKETS.RESOLVED_AT.isNull()).and(SERVICE_TICKETS.PAUSED_SINCE.isNull());
                sent += check(s, p, w, t, now, "resolve", t.getResolveDueAt(), policy.resolve(), policy, holidays,
                        SERVICE_TICKETS.RESOLVE_BREACHED, SERVICE_TICKETS.RESOLVE_WARNED, still);
            }
        }
        return sent;
    }

    private int check(Snapshot s, Snapshot.ProjectRow p, WorkPackagesRecord w, ServiceTicketsRecord t, Instant now, String kind, OffsetDateTime due,
                      Duration target, ServiceDeskConfig.Policy policy, Set<LocalDate> holidays,
                      TableField<ServiceTicketsRecord, Boolean> breached, TableField<ServiceTicketsRecord, Boolean> warned, Condition still) {
        if (!due.toInstant().isAfter(now)) {
            // set once: a second run, or another instance, finds it already set
            int marked = db.update(SERVICE_TICKETS).set(breached, true).set(warned, true)
                    .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(t.getWorkPackageId()), breached.isFalse(), still).execute();
            if (marked == 0) return 0;
            webhooks.enqueueSystem(s, w, "service_ticket.sla_breached", Map.of("sla", Map.of("kind", kind, "dueAt", due.toString())));
            return announce(s, p, w, t, "sla_breached", now);
        }
        if (warnedAlready(t, warned)) return 0;
        var left = SlaClock.workingTime(now, due.toInstant(), policy.calendar(), holidays);
        if (left.multipliedBy(5).compareTo(target) > 0) return 0; // more than 20 % left
        int marked = db.update(SERVICE_TICKETS).set(warned, true)
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(t.getWorkPackageId()), warned.isFalse(), still).execute();
        return marked == 0 ? 0 : announce(s, p, w, t, "sla_warning", now);
    }

    private static boolean warnedAlready(ServiceTicketsRecord t, TableField<ServiceTicketsRecord, Boolean> warned) {
        return Boolean.TRUE.equals(t.get(warned));
    }

    private int announce(Snapshot s, Snapshot.ProjectRow p, WorkPackagesRecord w, ServiceTicketsRecord t, String reason, Instant now) {
        var people = new LinkedHashSet<UUID>(Assignees.ids(assignees.of(w.getId())));
        if (t.getAssignmentGroupId() != null && s.groups().containsKey(t.getAssignmentGroupId())) people.addAll(s.groups().get(t.getAssignmentGroupId()).memberIds());
        var recipients = people.stream().filter(id -> canSee(s, id, p)).toList();
        if (recipients.isEmpty()) {
            recipients = s.projectUsers(p.id()).stream().map(Snapshot.UserRow::id).filter(id -> new Ctx(id, () -> s).can(p, "service.manage")).toList();
        }
        var at = now.atOffset(ZoneOffset.UTC);
        for (var id : recipients) {
            db.insertInto(NOTIFICATIONS).set(NOTIFICATIONS.RECIPIENT_ID, id).set(NOTIFICATIONS.WORK_PACKAGE_ID, w.getId()).set(NOTIFICATIONS.PROJECT_ID, p.id())
                    .set(NOTIFICATIONS.REASON, reason).set(NOTIFICATIONS.CREATED_AT, at).set(NOTIFICATIONS.UPDATED_AT, at).execute();
        }
        return recipients.size();
    }

    private static boolean canSee(Snapshot s, UUID userId, Snapshot.ProjectRow p) {
        var u = s.user(userId).filter(x -> "active".equals(x.status())).orElse(null);
        return u != null && new Ctx(userId, () -> s).can(p, "service.view");
    }
}

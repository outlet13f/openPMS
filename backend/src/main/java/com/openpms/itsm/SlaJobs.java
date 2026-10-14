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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Every minute: SLA deadlines that are close (20 % left) or past, announced once to the ticket's people (ITSM 1단계 §5). */
@Component
public class SlaJobs {
    private static final Logger log = LoggerFactory.getLogger(SlaJobs.class);
    private final DSLContext db;
    private final ServiceDeskConfig config;
    private final Assignees assignees;
    private final Webhooks webhooks;
    private final Current current;
    private final TransactionTemplate tx;

    public SlaJobs(DSLContext db, ServiceDeskConfig config, Assignees assignees, Webhooks webhooks, Current current, TransactionTemplate tx) {
        this.db = db;
        this.config = config;
        this.assignees = assignees;
        this.webhooks = webhooks;
        this.current = current;
        this.tx = tx;
    }

    /** @return the number of notifications sent */
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
            try {
                // one transaction per ticket: a bad ticket must not block everyone else's alerts
                Integer n = tx.execute(status -> check(s, p, w, t, now));
                sent += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.error("SLA check failed for work package #{}", w.getNumber(), e);
            }
        }
        return sent;
    }

    /** Both clocks of one ticket as the job read it; every update re-checks that the row is still what was read. */
    int check(Snapshot s, Snapshot.ProjectRow p, WorkPackagesRecord w, ServiceTicketsRecord seen, Instant now) {
        var policy = seen.getSlaPolicyId() == null ? null : config.policy(seen.getSlaPolicyId()).orElse(null);
        if (policy == null) return 0;
        var holidays = s.calendar().holidays();
        Condition open = SERVICE_TICKETS.WORK_PACKAGE_ID.eq(seen.getWorkPackageId()).and(SERVICE_TICKETS.RESOLVED_AT.isNull()).and(SERVICE_TICKETS.PAUSED_SINCE.isNull());
        int sent = 0;
        if (seen.getFirstRespondedAt() == null && seen.getFirstResponseDueAt() != null) {
            Condition still = open.and(SERVICE_TICKETS.FIRST_RESPONSE_DUE_AT.eq(seen.getFirstResponseDueAt())).and(SERVICE_TICKETS.FIRST_RESPONDED_AT.isNull());
            sent += clock(s, p, w, seen, now, "response", seen.getFirstResponseDueAt(), policy.firstResponse(), policy, holidays,
                    SERVICE_TICKETS.RESPONSE_BREACHED, SERVICE_TICKETS.RESPONSE_WARNED, still);
        }
        if (seen.getResolveDueAt() != null) {
            Condition still = open.and(SERVICE_TICKETS.RESOLVE_DUE_AT.eq(seen.getResolveDueAt()));
            sent += clock(s, p, w, seen, now, "resolve", seen.getResolveDueAt(), policy.resolve(), policy, holidays,
                    SERVICE_TICKETS.RESOLVE_BREACHED, SERVICE_TICKETS.RESOLVE_WARNED, still);
        }
        return sent;
    }

    private int clock(Snapshot s, Snapshot.ProjectRow p, WorkPackagesRecord w, ServiceTicketsRecord t, Instant now, String kind, OffsetDateTime due,
                      Duration target, ServiceDeskConfig.Policy policy, Set<LocalDate> holidays,
                      TableField<ServiceTicketsRecord, Boolean> breached, TableField<ServiceTicketsRecord, Boolean> warned, Condition still) {
        if (Boolean.TRUE.equals(t.get(breached))) return 0;
        if (!due.toInstant().isAfter(now)) {
            // set once: a second run, or another instance, finds it already set
            int marked = db.update(SERVICE_TICKETS).set(breached, true).set(warned, true).where(still, breached.isFalse()).execute();
            if (marked == 0) return 0;
            webhooks.enqueueSystem(s, w, "service_ticket.sla_breached", Map.of("sla", Map.of("kind", kind, "dueAt", due.toString())));
            return announce(s, p, w, t, "sla_breached", now);
        }
        if (warnedAlready(t, warned)) return 0;
        var left = SlaClock.workingTime(now, due.toInstant(), policy.calendar(), holidays);
        if (left.multipliedBy(5).compareTo(target) > 0) return 0; // more than 20 % left
        int marked = db.update(SERVICE_TICKETS).set(warned, true).where(still, warned.isFalse()).execute();
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

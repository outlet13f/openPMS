package com.openpms.itsm;

import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.USER_GROUPS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.ServiceTicketsRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * What a service desk adds to saving a work package (docs/superpowers/specs/2026-10-14-itsm-design.md §5): the
 * service_tickets row and its SLA. Called by WorkPackageService inside its transaction; never calls it back.
 */
@Component
public class ServiceDesk {
    public record Draft(UUID requesterId, String channel, int impact, int urgency, UUID groupId) {}

    private final DSLContext db;
    private final ServiceDeskConfig config;
    private final ServiceTickets tickets;
    private final Approvals approvals;

    public ServiceDesk(DSLContext db, ServiceDeskConfig config, ServiceTickets tickets, Approvals approvals) {
        this.db = db;
        this.config = config;
        this.tickets = tickets;
        this.approvals = approvals;
    }

    public boolean applies(Snapshot.ProjectRow p, UUID typeId) {
        return p.enabledModules().contains(ItsmIds.MODULE) && ItsmIds.TICKET_TYPES.contains(typeId);
    }

    /** Reads requester, impact, urgency and group from a create request. The requester is the caller unless an agent names someone. */
    public Draft draft(Ctx ctx, Snapshot.ProjectRow p, Map<String, Object> in, String channel) {
        var requesterId = "portal".equals(channel) ? ctx.userId() : In.uuid(in, "requesterId", "requester");
        if (requesterId == null) requesterId = ctx.userId();
        var requester = ctx.s().user(requesterId).filter(u -> "active".equals(u.status())).orElse(null);
        if (requester == null) throw ApiException.field("requester", "요청자는 활성 사용자여야 합니다.");
        return new Draft(requesterId, channel, level(in, "impact"), level(in, "urgency"), "portal".equals(channel) ? null : group(in));
    }

    static int level(Map<String, Object> in, String key) {
        var v = In.integer(in, key, key);
        if (v == null) return 2;
        if (v < 1 || v > 3) throw ApiException.field(key, "1(높음)·2(보통)·3(낮음) 중에서 고르세요.");
        return v;
    }

    UUID group(Map<String, Object> in) {
        var id = In.uuid(in, "assignmentGroupId", "assignmentGroup");
        if (id != null && !db.fetchExists(USER_GROUPS, USER_GROUPS.ID.eq(id))) throw ApiException.field("assignmentGroup", "알 수 없는 그룹입니다.");
        return id;
    }

    public UUID priorityFor(Snapshot.ProjectRow p, Draft d) {
        return config.matrixPriority(p.id(), d.impact(), d.urgency()).orElse(null);
    }

    public void created(Ctx ctx, WorkPackagesRecord w, Draft d) {
        var t = db.newRecord(SERVICE_TICKETS);
        t.setWorkPackageId(w.getId());
        t.setRequesterId(d.requesterId());
        t.setChannel(d.channel());
        t.setImpact((short) d.impact());
        t.setUrgency((short) d.urgency());
        t.setAssignmentGroupId(d.groupId());
        t.setPausedTotalSeconds(0L); // a new record does not carry the column default
        t.setCreatedAt(w.getCreatedAt());
        t.setUpdatedAt(w.getCreatedAt());
        // made straight in a closed or SLA-pause status (an agent's choice, or a copy): the clocks start in that state
        var status = ctx.s().status(w.getStatusId()).orElseThrow();
        if (status.isClosed()) t.setResolvedAt(w.getCreatedAt());
        else if (status.slaPause()) t.setPausedSince(w.getCreatedAt());
        recompute(ctx, t, w, Instant.now());
        t.store();
    }

    /**
     * A copy of a ticket (작업 복사, 프로젝트 복사): the original's requester, impact, urgency and group, and no receipt mail
     * for it (the requester asked once). The SLA runs from the copy's own start and status.
     *
     * @param modulesOf the project whose modules decide: the target, or for a project copy the template it took them from
     */
    public void copied(Ctx ctx, Snapshot.ProjectRow modulesOf, UUID originalId, WorkPackagesRecord copy) {
        if (!applies(modulesOf, copy.getTypeId())) return;
        // a copy has no approval rows: 승인 대기 would be a paused ticket nobody can decide, so it starts as 접수
        boolean wasAwaiting = ItsmIds.STATUS_AWAITING_APPROVAL.equals(copy.getStatusId());
        if (wasAwaiting) {
            db.update(WORK_PACKAGES).set(WORK_PACKAGES.STATUS_ID, ItsmIds.STATUS_RECEIVED).where(WORK_PACKAGES.ID.eq(copy.getId())).execute();
            copy.setStatusId(ItsmIds.STATUS_RECEIVED);
            copy.changed(WORK_PACKAGES.STATUS_ID, false);
        }
        var original = tickets.of(originalId).orElse(null);
        var t = tickets.of(copy.getId()).orElse(null);
        if (t == null) {
            if (original == null) return;
            created(ctx, copy, new Draft(original.getRequesterId(), "agent", original.getImpact(), original.getUrgency(), original.getAssignmentGroupId()));
            t = tickets.of(copy.getId()).orElseThrow();
        } else if (original != null) {
            t.setRequesterId(original.getRequesterId());
            t.setImpact(original.getImpact());
            t.setUrgency(original.getUrgency());
            t.setAssignmentGroupId(original.getAssignmentGroupId());
        }
        if (wasAwaiting && t.getPausedSince() != null) {
            // the ticket was made while still in 승인 대기 (single copy): its clock starts running in 접수
            t.setPausedSince(null);
            recompute(ctx, t, copy, Instant.now());
        }
        t.setRequesterMailedAt(at(Instant.now()));
        t.setRequesterMailedStatusId(copy.getStatusId());
        if (t.changed()) t.store();
    }

    public void updated(Ctx ctx, WorkPackagesRecord before, WorkPackagesRecord after) {
        var p = ctx.s().project(after.getProjectId()).orElseThrow();
        boolean was = applies(p, before.getTypeId()), is = applies(p, after.getTypeId());
        if (was != is) throw ApiException.field("type", "티켓 유형(장애·서비스 요청)과 다른 유형 사이는 바꿀 수 없습니다.");
        if (!is) return;
        var t = tickets.of(after.getId()).orElse(null);
        if (t == null) return; // made before the module was switched on
        var now = Instant.now();
        var s = ctx.s();
        var from = s.status(before.getStatusId()).orElseThrow();
        var to = s.status(after.getStatusId()).orElseThrow();
        boolean statusChanged = !from.id().equals(to.id());
        if (statusChanged) {
            if (to.slaPause() && t.getPausedSince() == null && !to.isClosed()) t.setPausedSince(at(now));
            if ((!to.slaPause() || to.isClosed()) && t.getPausedSince() != null) closePause(ctx, t, after, now);
            if (to.isClosed() && !from.isClosed()) t.setResolvedAt(at(now));
            if (!to.isClosed() && from.isClosed()) t.setResolvedAt(null);
            // leaving 승인 대기 is the approvers' decision (or the end of the request), not an answer to the requester
            if (!from.id().equals(ItsmIds.STATUS_AWAITING_APPROVAL)) firstResponse(ctx, t, now);
            // out of 승인 대기 other than by the last approval (cancelled, an administrator's change): the open approvals are skipped
            if (from.id().equals(ItsmIds.STATUS_AWAITING_APPROVAL)) approvals.skipRemaining(ctx, after);
            // the requester made this change themselves, so portal-mail must not announce it back (or mistake it for "already told")
            if (ctx.userId().equals(t.getRequesterId())) t.setRequesterMailedStatusId(to.id());
        }
        if (statusChanged || !Objects.equals(before.getPriorityId(), after.getPriorityId()) || !Objects.equals(before.getTypeId(), after.getTypeId())) {
            recompute(ctx, t, after, now);
        }
        if (t.changed()) t.store();
    }

    public void commented(Ctx ctx, WorkPackagesRecord w, boolean internal) {
        if (internal) return;
        var t = tickets.of(w.getId()).orElse(null);
        if (t == null) return;
        firstResponse(ctx, t, Instant.now());
        if (t.changed()) t.store();
    }

    private void firstResponse(Ctx ctx, ServiceTicketsRecord t, Instant now) {
        if (t.getFirstRespondedAt() == null && !ctx.userId().equals(t.getRequesterId())) t.setFirstRespondedAt(at(now));
    }

    private void closePause(Ctx ctx, ServiceTicketsRecord t, WorkPackagesRecord w, Instant now) {
        var cal = config.policyFor(w.getProjectId(), w.getTypeId(), w.getPriorityId()).map(ServiceDeskConfig.Policy::calendar)
                .orElse(SlaClock.Calendar.always(ZoneId.of("Asia/Seoul")));
        var paused = SlaClock.workingTime(t.getPausedSince().toInstant(), now, cal, ctx.s().calendar().holidays());
        t.setPausedTotalSeconds(t.getPausedTotalSeconds() + paused.toSeconds());
        t.setPausedSince(null);
    }

    /** Deadlines from the ticket's start, the current policy and the pauses so far. Breaches stay; a later deadline may warn again. */
    private void recompute(Ctx ctx, ServiceTicketsRecord t, WorkPackagesRecord w, Instant now) {
        var policy = config.policyFor(w.getProjectId(), w.getTypeId(), w.getPriorityId()).orElse(null);
        if (policy == null) {
            t.setSlaPolicyId(null);
            t.setFirstResponseDueAt(null);
            t.setResolveDueAt(null);
            return;
        }
        var holidays = ctx.s().calendar().holidays();
        var start = t.getCreatedAt().toInstant();
        // Time paused before the first response (a request waiting for approval starts paused) does not count against the
        // response target: it extends the deadline like it extends the resolve one. Once answered the deadline is final and
        // later pauses (답변 대기) or policy changes leave it as it was, so a recompute never shrinks it back.
        var respond = t.getFirstRespondedAt() != null && t.getFirstResponseDueAt() != null ? t.getFirstResponseDueAt()
                : at(SlaClock.add(start, policy.firstResponse().plus(Duration.ofSeconds(t.getPausedTotalSeconds())), policy.calendar(), holidays));
        var resolve = at(SlaClock.add(start, policy.resolve().plus(Duration.ofSeconds(t.getPausedTotalSeconds())), policy.calendar(), holidays));
        if (!isSame(respond, t.getFirstResponseDueAt()) && respond.toInstant().isAfter(now)) t.setResponseWarned(false);
        if (!isSame(resolve, t.getResolveDueAt()) && resolve.toInstant().isAfter(now)) t.setResolveWarned(false);
        t.setSlaPolicyId(policy.id());
        t.setFirstResponseDueAt(respond);
        t.setResolveDueAt(resolve);
    }

    private static boolean isSame(OffsetDateTime a, OffsetDateTime b) {
        return b != null && a.isEqual(b);
    }

    private static OffsetDateTime at(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}

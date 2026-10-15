package com.openpms.itsm;

import static com.openpms.db.Tables.CATALOG_ITEMS;
import static com.openpms.db.Tables.CATALOG_ITEM_APPROVAL_STEPS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_GROUPS;

import com.openpms.core.Ctx;
import com.openpms.db.tables.records.WorkPackagesRecord;
import com.openpms.wp.Journals;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Approvals of a catalog request (docs/superpowers/specs/2026-10-14-itsm-phase2-catalog-design.md §3): steps in order,
 * anyone of a step decides it, one rejection ends the request.
 */
@Component
public class Approvals {
    public static final String MANAGER = "requester_manager";
    public static final String GROUP_LEAD = "group_lead";

    public record StepRule(int stepNo, String kind) {}

    public record Planned(int stepNo, String kind, UUID approverId, String decision, String skipReason) {}

    /** One stored approval row of a request. */
    public record TicketApproval(UUID id, int stepNo, String kind, UUID approverId, String decision, String skipReason, OffsetDateTime decidedAt,
                                 String comment) {}

    // Approvals must not depend on WorkPackageService (WorkPackageService -> ServiceDesk -> ... would cycle)
    private final DSLContext db;
    private final Journals journals;

    public Approvals(DSLContext db, Journals journals) {
        this.db = db;
        this.journals = journals;
    }

    private static final List<String> KIND_ORDER = List.of(MANAGER, GROUP_LEAD);

    /** The rows to create for a new request. Pure: the caller looks up the manager and the group lead. */
    public static List<Planned> plan(List<StepRule> rules, UUID requesterId, UUID managerId, UUID groupLeadId) {
        var sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(StepRule::stepNo).thenComparingInt(r -> KIND_ORDER.indexOf(r.kind())));
        var out = new ArrayList<Planned>();
        Integer current = null;
        for (int i = 0; i < sorted.size(); i++) {
            var r = sorted.get(i);
            var who = MANAGER.equals(r.kind()) ? managerId : groupLeadId;
            String skip = null;
            if (who == null) skip = "no_approver";
            else if (who.equals(requesterId)) skip = "requester";
            else {
                for (var earlier : out) {
                    if (earlier.stepNo() == r.stepNo() && earlier.skipReason() == null && Objects.equals(earlier.approverId(), who)) skip = "duplicate";
                }
            }
            if (skip != null) {
                out.add(new Planned(r.stepNo(), r.kind(), skip.equals("no_approver") ? null : who, "skipped", skip));
                continue;
            }
            if (current == null) current = r.stepNo();
            out.add(new Planned(r.stepNo(), r.kind(), who, r.stepNo() == current ? "pending" : "waiting", null));
        }
        return out;
    }

    public static boolean needsApproval(List<Planned> planned) {
        return planned.stream().anyMatch(p -> "pending".equals(p.decision()));
    }

    /* ---------- stored rows ---------- */

    /**
     * The rows for a new request of this item by this requester: the item's steps, the requester's manager and the
     * item group's lead. A locked or deleted manager or lead counts as none (they could never decide).
     */
    public List<Planned> planFor(UUID itemId, UUID requesterId) {
        var steps = db.selectFrom(CATALOG_ITEM_APPROVAL_STEPS).where(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID.eq(itemId))
                .fetch(s -> new StepRule(s.getStepNo(), s.getApproverKind()));
        if (steps.isEmpty()) return List.of();
        var manager = USERS.as("manager");
        var managerId = db.select(manager.ID).from(USERS).join(manager).on(manager.ID.eq(USERS.MANAGER_ID))
                .where(USERS.ID.eq(requesterId), manager.STATUS.eq("active")).fetchOne(manager.ID);
        var groupLeadId = db.select(USERS.ID).from(CATALOG_ITEMS).join(USER_GROUPS).on(USER_GROUPS.ID.eq(CATALOG_ITEMS.ASSIGNMENT_GROUP_ID))
                .join(USERS).on(USERS.ID.eq(USER_GROUPS.LEAD_USER_ID))
                .where(CATALOG_ITEMS.ID.eq(itemId), USERS.STATUS.eq("active")).fetchOne(USERS.ID);
        return plan(steps, requesterId, managerId, groupLeadId);
    }

    /** Stores the planned rows of a new request; every skipped one leaves a line in the activity. */
    public void store(Ctx ctx, WorkPackagesRecord w, List<Planned> planned) {
        var now = OffsetDateTime.now();
        for (var p : planned) {
            var id = db.insertInto(TICKET_APPROVALS)
                    .set(TICKET_APPROVALS.WORK_PACKAGE_ID, w.getId())
                    .set(TICKET_APPROVALS.STEP_NO, (short) p.stepNo())
                    .set(TICKET_APPROVALS.APPROVER_KIND, p.kind())
                    .set(TICKET_APPROVALS.APPROVER_ID, p.approverId())
                    .set(TICKET_APPROVALS.DECISION, p.decision())
                    .set(TICKET_APPROVALS.SKIP_REASON, p.skipReason())
                    .set(TICKET_APPROVALS.DECIDED_AT, "skipped".equals(p.decision()) ? now : null)
                    .returning(TICKET_APPROVALS.ID).fetchOne().getId();
            if ("skipped".equals(p.decision())) journal(ctx, w, id, p.stepNo() + "단계 " + kindName(p.kind()) + " 건너뜀: " + skipText(p.skipReason()));
        }
    }

    /** A request's rows in step order (the manager before the group lead within a step). */
    public List<TicketApproval> of(UUID wpId) {
        return db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(wpId)).fetch().stream()
                .map(r -> new TicketApproval(r.getId(), r.getStepNo(), r.getApproverKind(), r.getApproverId(), r.getDecision(), r.getSkipReason(),
                        r.getDecidedAt(), r.getComment()))
                .sorted(Comparator.comparingInt(TicketApproval::stepNo).thenComparingInt(a -> KIND_ORDER.indexOf(a.kind())))
                .toList();
    }

    /**
     * The request left 승인 대기 some other way than the last approval (a rejection, a cancellation, an administrator's
     * change): the rows still waiting for a decision are skipped, with one line in the activity when any were.
     */
    public void skipRemaining(Ctx ctx, WorkPackagesRecord w) {
        var ids = db.update(TICKET_APPROVALS).set(TICKET_APPROVALS.DECISION, "skipped").set(TICKET_APPROVALS.SKIP_REASON, "left_approval")
                .set(TICKET_APPROVALS.DECIDED_AT, OffsetDateTime.now())
                .where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(w.getId()), TICKET_APPROVALS.DECISION.in("pending", "waiting"))
                .returning(TICKET_APPROVALS.ID).fetch(TICKET_APPROVALS.ID);
        if (!ids.isEmpty()) journal(ctx, w, ids.get(0), "남은 승인 건너뜀");
    }

    /** One line in the request's activity, keyed "approval:<row id>" (Journals shows it as 승인). */
    public void journal(Ctx ctx, WorkPackagesRecord w, UUID approvalId, String text) {
        var changes = new LinkedHashMap<String, List<Object>>();
        changes.put("approval:" + approvalId, Journals.listOf(null, text));
        var j = journals.write(ctx, w, changes, "", null, false);
        journals.fanOut(ctx, w, j, List.of());
    }

    static String kindName(String kind) {
        return MANAGER.equals(kind) ? "관리자" : "그룹 책임자";
    }

    private static final Map<String, String> SKIP_TEXT = Map.of("no_approver", "승인자 없음", "requester", "요청자 본인", "duplicate", "같은 승인자",
            "step_done", "같은 단계에서 결정됨", "left_approval", "승인 대기에서 나감");

    static String skipText(String reason) {
        return SKIP_TEXT.getOrDefault(reason, String.valueOf(reason));
    }
}

package com.openpms.itsm;

import static com.openpms.db.Tables.ATTACHMENTS;
import static com.openpms.db.Tables.CATALOG_ITEMS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.Html;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.customfield.CustomFields;
import com.openpms.db.tables.records.AttachmentsRecord;
import com.openpms.db.tables.records.TicketApprovalsRecord;
import com.openpms.wp.ScheduleStore;
import com.openpms.wp.WorkPackageService;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * The approver's side of a catalog request (docs/superpowers/specs/2026-10-14-itsm-phase2-catalog-design.md §3) and the
 * agent's approval panel. An approver need not be a member: every approver read and write is narrowed to
 * ticket_approvals.approver_id = the caller and decision = 'pending'; anything else is the same 404.
 */
@Service
public class ApprovalService {
    public record ApprovalSummary(UUID id, long number, String subject, String requester, String item, String desk, int stepNo,
                                  OffsetDateTime requestedAt) {}

    public record ApprovalDetail(UUID id, long number, String subject, String descriptionHtml, String requester, String item, String desk, int stepNo,
                                 List<PortalService.FieldValue> fields, List<PortalService.PortalFile> files, List<PortalService.PortalApproval> steps) {}

    public record AgentApproval(UUID id, int stepNo, String kind, Dto.UserRef approver, String decision, String skipReason, OffsetDateTime decidedAt,
                                String comment) {}

    static final String NOT_FOUND = "승인 요청을 찾을 수 없습니다.";

    private final DSLContext db;
    private final WorkPackageService workPackages;
    private final Approvals approvals;
    private final PortalService portal;
    private final ServiceCatalog catalog;
    private final CustomFields customFields;
    private final ScheduleStore schedules;

    public ApprovalService(DSLContext db, WorkPackageService workPackages, Approvals approvals, PortalService portal, ServiceCatalog catalog,
                           CustomFields customFields, ScheduleStore schedules) {
        this.schedules = schedules;
        this.db = db;
        this.workPackages = workPackages;
        this.approvals = approvals;
        this.portal = portal;
        this.catalog = catalog;
        this.customFields = customFields;
    }

    private static boolean live(Snapshot s, UUID projectId) {
        return s.project(projectId).filter(x -> x.enabledModules().contains(ItsmIds.MODULE) && x.archivedAt() == null).isPresent();
    }

    /* ---------- my approvals (the portal's 내 승인함) ---------- */

    /** My rows whose turn has come, oldest request first. */
    public List<ApprovalSummary> inbox(Ctx ctx) {
        var s = ctx.s();
        return db.select(TICKET_APPROVALS.ID, WORK_PACKAGES.NUMBER, WORK_PACKAGES.SUBJECT, SERVICE_TICKETS.REQUESTER_ID, CATALOG_ITEMS.NAME,
                        WORK_PACKAGES.PROJECT_ID, TICKET_APPROVALS.STEP_NO, WORK_PACKAGES.CREATED_AT)
                .from(TICKET_APPROVALS)
                .join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(TICKET_APPROVALS.WORK_PACKAGE_ID))
                .join(SERVICE_TICKETS).on(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(WORK_PACKAGES.ID))
                .leftJoin(CATALOG_ITEMS).on(CATALOG_ITEMS.ID.eq(SERVICE_TICKETS.CATALOG_ITEM_ID))
                .where(TICKET_APPROVALS.APPROVER_ID.eq(ctx.userId()), TICKET_APPROVALS.DECISION.eq("pending"))
                .orderBy(WORK_PACKAGES.CREATED_AT, WORK_PACKAGES.NUMBER).limit(200)
                .fetch().stream().filter(r -> live(s, r.value6()))
                .map(r -> new ApprovalSummary(r.value1(), r.value2(), r.value3(), Refs.user(s, r.value4()).name(), r.value5(),
                        s.project(r.value6()).map(Snapshot.ProjectRow::name).orElse(""), r.value7(), r.value8()))
                .toList();
    }

    /** My pending row and its request, or the 404 (not mine, not my turn yet, already decided, gone or no longer a desk). */
    private Mine mine(Ctx ctx, UUID approvalId) {
        var row = db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.ID.eq(approvalId), TICKET_APPROVALS.APPROVER_ID.eq(ctx.userId()),
                TICKET_APPROVALS.DECISION.eq("pending")).fetchOne();
        if (row == null) throw ApiException.notFound(NOT_FOUND);
        var f = found(ctx, row.getWorkPackageId());
        if (f == null) throw ApiException.notFound(NOT_FOUND);
        return new Mine(row, f);
    }

    /** The 404 unless the row is my pending one. */
    public void check(Ctx ctx, UUID approvalId) {
        mine(ctx, approvalId);
    }

    private record Mine(TicketApprovalsRecord row, WorkPackageService.Found f) {}

    /** The request as a Found, without a visibility check (the approver is not a member); null when it is not a live desk's. */
    private WorkPackageService.Found found(Ctx ctx, UUID wpId) {
        var w = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(wpId)).fetchOne();
        if (w == null || !live(ctx.s(), w.getProjectId())) return null;
        return new WorkPackageService.Found(w, ctx.s().project(w.getProjectId()).orElseThrow());
    }

    /** What the approver reads: the request, the item's form fields only, its files and how far the steps are (no comments). */
    public ApprovalDetail detail(Ctx ctx, UUID approvalId) {
        var m = mine(ctx, approvalId);
        var s = ctx.s();
        var w = m.f().wp();
        var p = m.f().project();
        var ticket = db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(w.getId())).fetchOne();
        var item = ticket == null || ticket.getCatalogItemId() == null ? null : catalog.item(p.id(), ticket.getCatalogItemId()).orElse(null);
        var formIds = new HashSet<UUID>();
        if (item != null) portal.itemFields(s, p, item).forEach(x -> formIds.add(x.id()));
        var fields = customFields.values(s, w).entrySet().stream()
                .filter(e -> formIds.contains(UUID.fromString(e.getKey())))
                .map(e -> new PortalService.FieldValue(s.customField(UUID.fromString(e.getKey())).map(Snapshot.CustomFieldRow::name).orElse(""),
                        PortalService.display(e.getValue())))
                .toList();
        return new ApprovalDetail(m.row().getId(), w.getNumber(), w.getSubject(), w.getDescriptionHtml(),
                ticket == null ? "" : Refs.user(s, ticket.getRequesterId()).name(), item == null ? null : item.name(), p.name(), m.row().getStepNo(),
                fields, files(w.getId()), portal.portalApprovals(s, w.getId()));
    }

    private List<PortalService.PortalFile> files(UUID wpId) {
        return db.selectFrom(ATTACHMENTS).where(ATTACHMENTS.WORK_PACKAGE_ID.eq(wpId)).orderBy(ATTACHMENTS.CREATED_AT)
                .fetch(a -> new PortalService.PortalFile(a.getId(), a.getFileName(), a.getByteSize(), a.getCreatedAt()));
    }

    /** A file of the very request my pending row belongs to. */
    public AttachmentsRecord attachment(Ctx ctx, UUID approvalId, UUID fileId) {
        var m = mine(ctx, approvalId);
        var a = db.selectFrom(ATTACHMENTS).where(ATTACHMENTS.ID.eq(fileId), ATTACHMENTS.WORK_PACKAGE_ID.eq(m.f().wp().getId())).fetchOne();
        if (a == null) throw ApiException.notFound("파일을 찾을 수 없습니다.");
        return a;
    }

    /**
     * Approve or reject my pending row. The conditional update makes a decision count once. Lock order is the same as every
     * other writer of a work package: the schedule advisory lock first (the status change below goes through the scheduler),
     * then the request row, so a decision, an approver change and a status save of one request wait for each other and
     * never lock in opposite orders.
     */
    @Transactional
    public String decide(Ctx ctx, UUID approvalId, boolean approve, String comment) {
        var row = db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.ID.eq(approvalId), TICKET_APPROVALS.APPROVER_ID.eq(ctx.userId())).fetchOne();
        // not mine, not my turn yet (waiting) or no longer mine to decide (skipped): the same 404
        if (row == null || row.getDecision().equals("waiting") || row.getDecision().equals("skipped")) throw ApiException.notFound(NOT_FOUND);
        if (found(ctx, row.getWorkPackageId()) == null) throw ApiException.notFound(NOT_FOUND);
        var reason = comment == null ? "" : comment.trim();
        if (!approve && reason.isEmpty()) throw ApiException.field("comment", "반려 사유를 입력하세요.");
        schedules.lock();
        lock(row.getWorkPackageId());
        int won = db.update(TICKET_APPROVALS).set(TICKET_APPROVALS.DECISION, approve ? "approved" : "rejected")
                .set(TICKET_APPROVALS.DECIDED_AT, OffsetDateTime.now()).set(TICKET_APPROVALS.COMMENT, reason)
                .where(TICKET_APPROVALS.ID.eq(approvalId), TICKET_APPROVALS.APPROVER_ID.eq(ctx.userId()), TICKET_APPROVALS.DECISION.eq("pending")).execute();
        if (won == 0) throw ApiException.conflict("이미 처리된 승인입니다.", "승인함을 새로 고치세요.", null);
        var f = found(ctx, row.getWorkPackageId());
        var name = Refs.user(ctx.s(), ctx.userId()).name();
        approvals.journal(ctx, f.wp(), approvalId, row.getStepNo() + "단계 " + name + (approve ? " 승인" : " 반려"));
        if (!approve) {
            approvals.skipRemaining(ctx, f.wp());
            workPackages.commentForApproval(ctx, f, plainToHtml(reason));
            workPackages.statusForApproval(ctx, found(ctx, row.getWorkPackageId()), ItsmIds.STATUS_REJECTED);
            return "rejected";
        }
        // anyone of the step decides it: the others of the step are done, the next step's waiting rows become pending
        db.update(TICKET_APPROVALS).set(TICKET_APPROVALS.DECISION, "skipped").set(TICKET_APPROVALS.SKIP_REASON, "step_done")
                .set(TICKET_APPROVALS.DECIDED_AT, OffsetDateTime.now())
                .where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(row.getWorkPackageId()), TICKET_APPROVALS.STEP_NO.eq(row.getStepNo()),
                        TICKET_APPROVALS.DECISION.eq("pending")).execute();
        var next = db.select(DSL.min(TICKET_APPROVALS.STEP_NO)).from(TICKET_APPROVALS)
                .where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(row.getWorkPackageId()), TICKET_APPROVALS.DECISION.eq("waiting")).fetchOne(0, Short.class);
        if (next != null) {
            db.update(TICKET_APPROVALS).set(TICKET_APPROVALS.DECISION, "pending")
                    .where(TICKET_APPROVALS.WORK_PACKAGE_ID.eq(row.getWorkPackageId()), TICKET_APPROVALS.STEP_NO.eq(next),
                            TICKET_APPROVALS.DECISION.eq("waiting")).execute();
            return "approved";
        }
        workPackages.statusForApproval(ctx, found(ctx, row.getWorkPackageId()), ItsmIds.STATUS_RECEIVED);
        return "approved";
    }

    /** The reject reason is plain text from a textarea: escaped, one paragraph per line. */
    static String plainToHtml(String text) {
        var sb = new StringBuilder();
        for (var line : text.split("\\R")) if (!line.isBlank()) sb.append("<p>").append(HtmlUtils.htmlEscape(line.strip())).append("</p>");
        return sb.toString();
    }

    /** Waits for (and holds until commit) the request row: deciders, approver changes and status saves of one request take turns. */
    private void lock(UUID wpId) {
        db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(wpId)).forUpdate().fetch();
    }

    /* ---------- the agent's approval panel ---------- */

    private WorkPackageService.Found agentFind(Ctx ctx, long number) {
        var f = workPackages.find(ctx, number);
        if (!ctx.can(f.project(), "service.view")) throw ApiException.notFound("작업을 찾을 수 없습니다.");
        return f;
    }

    /** Every row of the request, with who decided, why a row was skipped and the approvers' notes. */
    public List<AgentApproval> forAgent(Ctx ctx, long number) {
        var f = agentFind(ctx, number);
        return agentRows(ctx.s(), f.wp().getId());
    }

    private List<AgentApproval> agentRows(Snapshot s, UUID wpId) {
        return approvals.of(wpId).stream().map(a -> new AgentApproval(a.id(), a.stepNo(), a.kind(),
                a.approverId() == null ? null : Refs.user(s, a.approverId()), a.decision(), a.skipReason(), a.decidedAt(), a.comment())).toList();
    }

    /** An agent hands an undecided row (pending or waiting) to someone else: active and not the requester. */
    @Transactional
    public List<AgentApproval> replace(Ctx ctx, long number, UUID approvalId, UUID approverId) {
        var f = agentFind(ctx, number);
        ctx.require(f.project(), "service.handle", "티켓을 처리할 권한이 없습니다.");
        var w = f.wp();
        lock(w.getId());
        var row = db.selectFrom(TICKET_APPROVALS).where(TICKET_APPROVALS.ID.eq(approvalId), TICKET_APPROVALS.WORK_PACKAGE_ID.eq(w.getId())).fetchOne();
        if (row == null) throw ApiException.notFound(NOT_FOUND);
        if (!row.getDecision().equals("pending") && !row.getDecision().equals("waiting")) {
            throw ApiException.unprocessable("결정이 끝난 승인입니다.", "아직 결정하지 않은 승인의 승인자만 바꿀 수 있습니다.");
        }
        var s = ctx.s();
        var who = approverId == null ? null : s.user(approverId).filter(u -> "active".equals(u.status())).orElse(null);
        if (who == null) throw ApiException.field("approver", "승인자는 활성 사용자여야 합니다.");
        var requesterId = db.select(SERVICE_TICKETS.REQUESTER_ID).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(w.getId()))
                .fetchOne(SERVICE_TICKETS.REQUESTER_ID);
        if (approverId.equals(requesterId)) throw ApiException.field("approver", "요청자 본인은 승인자가 될 수 없습니다.");
        if (Objects.equals(approverId, row.getApproverId())) return agentRows(s, w.getId());
        int n = db.update(TICKET_APPROVALS).set(TICKET_APPROVALS.APPROVER_ID, approverId)
                // the new approver is told afresh (approval-mail)
                .setNull(TICKET_APPROVALS.NOTIFIED_AT).setNull(TICKET_APPROVALS.REMINDED_AT)
                .where(TICKET_APPROVALS.ID.eq(approvalId), TICKET_APPROVALS.DECISION.in("pending", "waiting")).execute();
        if (n == 0) throw ApiException.conflict("이미 처리된 승인입니다.", "승인 목록을 새로 고치세요.", null);
        var was = row.getApproverId() == null ? "없음" : Refs.user(s, row.getApproverId()).name();
        approvals.journal(ctx, w, approvalId, row.getStepNo() + "단계 승인자 변경: " + was + " → " + who.name());
        return agentRows(s, w.getId());
    }
}

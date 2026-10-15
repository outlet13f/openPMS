package com.openpms.itsm;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Current;
import com.openpms.wp.Journals;
import com.openpms.wp.WorkPackageService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An agent's view and edit of a ticket's ITSM fields; the rest of a ticket is an ordinary work package. */
@RestController
@RequestMapping("/api/v1")
public class ServiceTicketController {
    private final Current current;
    private final WorkPackageService workPackages;
    private final ServiceTickets tickets;
    private final ServiceDesk serviceDesk;
    private final ServiceDeskConfig config;
    private final Journals journals;
    private final ApprovalService approvals;

    public ServiceTicketController(Current current, WorkPackageService workPackages, ServiceTickets tickets, ServiceDesk serviceDesk,
                                   ServiceDeskConfig config, Journals journals, ApprovalService approvals) {
        this.approvals = approvals;
        this.current = current;
        this.workPackages = workPackages;
        this.tickets = tickets;
        this.serviceDesk = serviceDesk;
        this.config = config;
        this.journals = journals;
    }

    @GetMapping("/work-packages/{number}/service-ticket")
    public Dto.ServiceTicket get(@PathVariable long number) {
        var ctx = current.ctx();
        var f = workPackages.find(ctx, number);
        if (!ctx.can(f.project(), "service.view")) throw ApiException.notFound("작업을 찾을 수 없습니다.");
        return tickets.dto(ctx.s(), tickets.of(f.wp().getId()).orElseThrow(() -> ApiException.notFound("서비스 데스크 티켓이 아닙니다.")));
    }

    /** requesterId, impact, urgency, assignmentGroupId; applyMatrix also sets the priority from the matrix. */
    @PatchMapping("/work-packages/{number}/service-ticket")
    @Transactional
    public Dto.WorkPackage patch(@PathVariable long number, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var f = workPackages.find(ctx, number);
        if (!ctx.can(f.project(), "service.view")) throw ApiException.notFound("작업을 찾을 수 없습니다.");
        ctx.require(f.project(), "service.handle", "티켓을 처리할 권한이 없습니다.");
        var t = tickets.of(f.wp().getId()).orElseThrow(() -> ApiException.notFound("서비스 데스크 티켓이 아닙니다."));
        var changes = new LinkedHashMap<String, List<Object>>();
        if (In.has(in, "requesterId")) {
            var next = serviceDesk.draft(ctx, f.project(), Map.of("requesterId", String.valueOf(in.get("requesterId"))), "agent").requesterId();
            if (!next.equals(t.getRequesterId())) {
                changes.put("requesterId", Journals.listOf(t.getRequesterId().toString(), next.toString()));
                // a new requester starts fresh: portal-mail sends them a receipt and the portal shows everything as news
                t.setRequesterSeenAt(null);
                t.setRequesterMailedAt(null);
                t.setRequesterMailedStatusId(null);
            }
            t.setRequesterId(next);
        }
        for (var key : List.of("impact", "urgency")) {
            if (!In.has(in, key)) continue;
            short next = (short) ServiceDesk.level(in, key);
            short prev = "impact".equals(key) ? t.getImpact() : t.getUrgency();
            if (next != prev) changes.put(key, Journals.listOf((int) prev, (int) next));
            if ("impact".equals(key)) t.setImpact(next);
            else t.setUrgency(next);
        }
        if (In.has(in, "assignmentGroupId")) {
            var next = serviceDesk.group(in);
            if (!Objects.equals(next, t.getAssignmentGroupId())) {
                changes.put("assignmentGroupId", Journals.listOf(t.getAssignmentGroupId() == null ? null : t.getAssignmentGroupId().toString(),
                        next == null ? null : next.toString()));
            }
            t.setAssignmentGroupId(next);
        }
        if (t.changed()) t.store();
        if (!changes.isEmpty()) {
            var j = journals.write(ctx, f.wp(), changes, "", null, false);
            journals.fanOut(ctx, f.wp(), j, List.of());
        }
        if (Boolean.TRUE.equals(In.bool(in, "applyMatrix"))) {
            var priority = config.matrixPriority(f.project().id(), t.getImpact(), t.getUrgency()).orElse(null);
            if (priority != null && !priority.equals(f.wp().getPriorityId())) {
                return workPackages.update(ctx, number, Map.of("lockVersion", f.wp().getLockVersion(), "priorityId", priority.toString()));
            }
        }
        return workPackages.get(ctx, number);
    }

    /** The approval panel: every row of a catalog request (service.view). */
    @GetMapping("/work-packages/{number}/approvals")
    public List<ApprovalService.AgentApproval> approvals(@PathVariable long number) {
        return approvals.forAgent(current.ctx(), number);
    }

    /** {approverId}: hands an undecided row to someone else (service.handle). */
    @PatchMapping("/work-packages/{number}/approvals/{id}")
    public List<ApprovalService.AgentApproval> replaceApprover(@PathVariable long number, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
        return approvals.replace(current.ctx(), number, id, In.uuid(in, "approverId", "approver"));
    }
}

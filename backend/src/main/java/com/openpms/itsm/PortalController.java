package com.openpms.itsm;

import com.openpms.common.In;
import com.openpms.core.Current;
import com.openpms.wp.WorkPackageService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /portal: any signed-in employee, their own tickets only (PortalService). */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {
    private final Current current;
    private final PortalService portal;
    private final WorkPackageService workPackages;

    public PortalController(Current current, PortalService portal, WorkPackageService workPackages) {
        this.current = current;
        this.portal = portal;
        this.workPackages = workPackages;
    }

    @GetMapping("/desks")
    public List<PortalService.PortalDesk> desks() {
        return portal.desks(current.ctx());
    }

    @PostMapping("/desks/{identifier}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public PortalService.PortalTicket submit(@PathVariable String identifier, @RequestBody Map<String, Object> in) {
        return portal.submit(current.ctx(), identifier, in);
    }

    @GetMapping("/tickets")
    public List<PortalService.PortalSummary> list(@RequestParam(defaultValue = "all") String state) {
        return portal.list(current.ctx(), state);
    }

    @GetMapping("/tickets/{number}")
    public PortalService.PortalTicket get(@PathVariable long number) {
        return portal.open(current.ctx(), number);
    }

    /** A comment; on a paused ticket (답변 대기) it also moves it back to the agents when the 요청자 workflow allows it. */
    @PostMapping("/tickets/{number}/comments")
    @Transactional
    public PortalService.PortalTicket comment(@PathVariable long number, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var f = portal.find(ctx, number);
        workPackages.commentForPortal(ctx, f, In.str(in, "bodyHtml"));
        // re-read: the comment may have bumped the row, and the status check must see the current one
        var fresh = portal.find(ctx, number);
        var status = ctx.s().status(fresh.wp().getStatusId()).orElseThrow();
        var ticket = portal.ticket(ctx, fresh, false);
        if (status.slaPause() && ticket.allowedStatuses().stream().anyMatch(x -> x.id().equals(ItsmIds.STATUS_HANDLING))) {
            workPackages.statusForPortal(ctx, fresh, ItsmIds.STATUS_HANDLING, fresh.wp().getLockVersion());
        }
        return portal.open(ctx, number);
    }

    @PostMapping("/tickets/{number}/status")
    @Transactional
    public PortalService.PortalTicket status(@PathVariable long number, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var f = portal.find(ctx, number);
        var statusId = In.uuid(in, "statusId", "status");
        var lock = In.integer(in, "lockVersion", "lockVersion");
        if (statusId == null) throw com.openpms.common.ApiException.field("status", "상태를 고르세요.");
        workPackages.statusForPortal(ctx, f, statusId, lock == null ? -1 : lock);
        return portal.open(ctx, number);
    }
}

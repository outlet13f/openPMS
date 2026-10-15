package com.openpms.itsm;

import com.openpms.attachment.AttachmentStore;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Current;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /portal/approvals: any signed-in employee, their own pending approval rows only (ApprovalService). */
@RestController
@RequestMapping("/api/v1/portal/approvals")
public class ApprovalController {
    private final Current current;
    private final ApprovalService approvals;
    private final AttachmentStore store;

    public ApprovalController(Current current, ApprovalService approvals, AttachmentStore store) {
        this.current = current;
        this.approvals = approvals;
        this.store = store;
    }

    @GetMapping
    public List<ApprovalService.ApprovalSummary> inbox() {
        return approvals.inbox(current.ctx());
    }

    @GetMapping("/{id}")
    public ApprovalService.ApprovalDetail get(@PathVariable UUID id) {
        return approvals.detail(current.ctx(), id);
    }

    @GetMapping("/{id}/attachments/{fileId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @PathVariable UUID fileId) throws IOException {
        return store.serve(approvals.attachment(current.ctx(), id, fileId));
    }

    public record Decision(String decision) {}

    /** {decision: "approve" | "reject", comment}; a rejection needs a reason, which the requester reads. */
    @PostMapping("/{id}/decision")
    public Decision decide(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var d = In.str(in, "decision");
        if (!"approve".equals(d) && !"reject".equals(d)) {
            approvals.check(current.ctx(), id); // someone else's row is the 404 whatever the body says
            throw ApiException.field("decision", "승인 또는 반려를 고르세요.");
        }
        return new Decision(approvals.decide(current.ctx(), id, "approve".equals(d), In.str(in, "comment")));
    }
}

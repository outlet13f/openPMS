package com.openpms.wp;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Current;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Work package routes (frontend/src/api/http.ts workPackages). */
@RestController
@RequestMapping("/api/v1")
public class WorkPackageController {
    private final Current current;
    private final WorkPackageService service;
    private final WorkPackageTransfers transfers;

    public WorkPackageController(Current current, WorkPackageService service, WorkPackageTransfers transfers) {
        this.current = current;
        this.service = service;
        this.transfers = transfers;
    }

    @PostMapping("/work-packages/{number}/copy")
    public ResponseEntity<Dto.WorkPackage> copy(@PathVariable long number, @RequestBody Map<String, Object> input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transfers.copy(current.ctx(), number, input));
    }

    @PostMapping("/work-packages/{number}/move")
    public Dto.WorkPackage move(@PathVariable long number, @RequestBody Map<String, Object> input) {
        return transfers.move(current.ctx(), number, input);
    }

    @PostMapping("/work-packages/bulk")
    public WorkPackageTransfers.BulkResult bulk(@RequestBody Map<String, Object> input) {
        return transfers.bulk(current.ctx(), input);
    }

    @GetMapping("/projects/{projectId}/work-packages/form")
    public Dto.WorkPackageForm form(@PathVariable String projectId, @RequestParam(required = false) String typeId) {
        return service.form(current.ctx(), projectId, typeId);
    }

    @PostMapping("/projects/{projectId}/work-packages")
    public ResponseEntity<Dto.WorkPackage> create(@PathVariable String projectId, @RequestBody Map<String, Object> input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(current.ctx(), projectId, input));
    }

    @GetMapping("/work-packages/autocomplete")
    public List<Dto.WorkPackageRef> autocomplete(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String projectId) {
        return service.autocomplete(current.ctx(), q, projectId);
    }

    @GetMapping("/work-packages/{number}")
    public Dto.WorkPackage get(@PathVariable long number) {
        return service.get(current.ctx(), number);
    }

    @PatchMapping("/work-packages/{number}")
    public Dto.WorkPackage update(@PathVariable long number, @RequestBody Map<String, Object> patch) {
        return service.update(current.ctx(), number, patch);
    }

    @DeleteMapping("/work-packages/{number}")
    public ResponseEntity<Void> remove(@PathVariable long number, @RequestParam(defaultValue = "reparent") String children) {
        service.remove(current.ctx(), number, children);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/work-packages/{number}/allowed-statuses")
    public List<Dto.StatusRef> allowedStatuses(@PathVariable long number) {
        return service.allowedStatuses(current.ctx(), number);
    }

    @GetMapping("/work-packages/{number}/activities")
    public List<Dto.Journal> activities(@PathVariable long number) {
        return service.activities(current.ctx(), number);
    }

    @PostMapping("/work-packages/{number}/comments")
    public ResponseEntity<Dto.Journal> comment(@PathVariable long number, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.comment(current.ctx(), number, In.str(body, "bodyHtml")));
    }

    @PatchMapping("/comments/{id}")
    public Dto.Journal editComment(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.editComment(current.ctx(), id, In.str(body, "bodyHtml"));
    }

    @GetMapping("/work-packages/{number}/shares")
    public List<Dto.WorkPackageShare> shares(@PathVariable long number) {
        return service.shares(current.ctx(), number);
    }

    @PostMapping("/work-packages/{number}/shares")
    public ResponseEntity<Dto.WorkPackageShare> share(@PathVariable long number, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.share(current.ctx(), number, In.uuid(body, "userId", "userId"), In.str(body, "role")));
    }

    @PatchMapping("/shares/{id}")
    public Dto.WorkPackageShare updateShare(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return service.updateShare(current.ctx(), id, In.str(body, "role"));
    }

    @DeleteMapping("/shares/{id}")
    public ResponseEntity<Void> removeShare(@PathVariable UUID id) {
        service.removeShare(current.ctx(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/work-packages/{number}/watchers")
    public List<Dto.Watcher> watchers(@PathVariable long number) {
        return service.watchers(current.ctx(), number);
    }

    /** {"userId": "…"} adds someone; {"self": true} (or no body) watches it yourself. */
    @PostMapping("/work-packages/{number}/watchers")
    public ResponseEntity<Void> watch(@PathVariable long number, @RequestBody(required = false) Map<String, Object> body) {
        var ctx = current.ctx();
        var userId = body != null && In.has(body, "userId") ? In.uuid(body, "userId", "userId") : ctx.userId();
        service.addWatcher(ctx, number, userId);
        return ResponseEntity.noContent().build();
    }

    /** "me" or a user id. */
    @DeleteMapping("/work-packages/{number}/watchers/{userId}")
    public ResponseEntity<Void> unwatch(@PathVariable long number, @PathVariable String userId) {
        var ctx = current.ctx();
        UUID id;
        try {
            id = "me".equals(userId) ? ctx.userId() : UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("주시자를 찾을 수 없습니다.");
        }
        service.removeWatcher(ctx, number, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/work-packages/{number}/relations")
    public Dto.RelationGroups relations(@PathVariable long number) {
        return service.relations(current.ctx(), number);
    }

    @PostMapping("/work-packages/{number}/relations")
    public ResponseEntity<Void> addRelation(@PathVariable long number, @RequestBody Map<String, Object> body) {
        var to = In.integer(body, "toNumber", "toNumber");
        if (to == null) throw ApiException.field("toNumber", "연결할 작업을 고르세요.");
        service.addRelation(current.ctx(), number, In.str(body, "type"), to, In.integer(body, "lag", "lag"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/relations/{id}")
    public ResponseEntity<Void> removeRelation(@PathVariable UUID id) {
        service.removeRelation(current.ctx(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/work-packages/{number}/schedule-preview")
    public Dto.SchedulePreview preview(@PathVariable long number, @RequestBody Map<String, Object> patch) {
        return service.schedulePreview(current.ctx(), number, patch);
    }
}

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

    public WorkPackageController(Current current, WorkPackageService service) {
        this.current = current;
        this.service = service;
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

    @PostMapping("/work-packages/{number}/watchers")
    public ResponseEntity<Void> watch(@PathVariable long number, @RequestBody(required = false) Map<String, Object> body) {
        // only "watch it myself" for now; adding other people is wp.add_watchers (should-have, S07 주시자 탭)
        if (body != null && In.has(body, "userId")) throw ApiException.unprocessable("지원하지 않는 요청입니다.", "다른 사람을 주시자로 추가하는 기능은 아직 없습니다.");
        service.setWatching(current.ctx(), number, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/work-packages/{number}/watchers/me")
    public ResponseEntity<Void> unwatch(@PathVariable long number) {
        service.setWatching(current.ctx(), number, false);
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

package com.openpms.cicd;

import com.openpms.core.Current;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 › CI/CD › 환경. Viewing needs cicd.view; every change needs cicd.manage. */
@RestController
@RequestMapping("/api/v1/projects/{project}/cicd/environments")
public class CicdEnvironmentController {
    private final Current current;
    private final CicdEnvironments environments;

    public CicdEnvironmentController(Current current, CicdEnvironments environments) {
        this.current = current;
        this.environments = environments;
    }

    @GetMapping
    public CicdEnvironments.EnvironmentsView view(@PathVariable String project) {
        var ctx = current.ctx();
        return environments.view(ctx, environments.viewable(ctx, project));
    }

    @PostMapping
    public ResponseEntity<CicdEnvironments.EnvironmentDto> create(@PathVariable String project, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        return ResponseEntity.status(HttpStatus.CREATED).body(environments.create(ctx, environments.manageable(ctx, project), in));
    }

    @PatchMapping("/{id}")
    public CicdEnvironments.EnvironmentDto update(@PathVariable String project, @PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        return environments.update(ctx, environments.manageable(ctx, project), id, in);
    }

    @PutMapping("/order")
    public CicdEnvironments.EnvironmentsView reorder(@PathVariable String project, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        return environments.reorder(ctx, environments.manageable(ctx, project), in);
    }

    @DeleteMapping("/{id}")
    public CicdEnvironments.Removed remove(@PathVariable String project, @PathVariable UUID id) {
        var ctx = current.ctx();
        return environments.remove(ctx, environments.manageable(ctx, project), id);
    }
}

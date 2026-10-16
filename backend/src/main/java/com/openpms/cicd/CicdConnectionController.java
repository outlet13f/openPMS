package com.openpms.cicd;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 › CI/CD 연동. Administrators and holders of global.manage_cicd. */
@RestController
@RequestMapping("/api/v1/admin/cicd/connections")
public class CicdConnectionController {
    private final Current current;
    private final CicdConnections connections;

    public CicdConnectionController(Current current, CicdConnections connections) {
        this.current = current;
        this.connections = connections;
    }

    @GetMapping
    public List<CicdConnections.ConnectionDto> list() {
        return connections.list(manager());
    }

    @PostMapping
    public ResponseEntity<CicdConnections.CreatedConnection> create(@RequestBody Map<String, Object> in) {
        var ctx = manager();
        return ResponseEntity.status(HttpStatus.CREATED).body(connections.create(ctx, in));
    }

    @PatchMapping("/{id}")
    public CicdConnections.ConnectionDto update(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        return connections.update(manager(), id, in);
    }

    /** System administrators only (a delegated CI/CD 연동 관리자 gets 403). */
    @PutMapping("/{id}/projects")
    public CicdConnections.ConnectionDto setProjects(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        return connections.setProjects(manager(), id, in);
    }

    @PostMapping("/{id}/secret")
    public CicdConnections.Secret rotate(@PathVariable UUID id) {
        return connections.rotateSecret(manager(), id);
    }

    @PostMapping("/{id}/check")
    public CicdConnections.CheckResult check(@PathVariable UUID id) {
        manager();
        return connections.check(id);
    }

    /** The Jenkinsfile or Argo CD settings to paste, with this connection's webhook address (never the secret). */
    @GetMapping("/{id}/templates")
    public CicdConnections.Templates templates(@PathVariable UUID id) {
        manager();
        return connections.templates(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        connections.delete(manager(), id);
        return ResponseEntity.noContent().build();
    }

    private com.openpms.core.Ctx manager() {
        var ctx = current.ctx();
        CicdConnections.requireManager(ctx);
        return ctx;
    }
}

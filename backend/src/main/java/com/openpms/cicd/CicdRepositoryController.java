package com.openpms.cicd;

import com.openpms.core.Current;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 › CI/CD 저장소. Viewing needs cicd.view; searching, attaching and detaching need cicd.manage. */
@RestController
@RequestMapping("/api/v1/projects/{project}/cicd")
public class CicdRepositoryController {
    private final Current current;
    private final CicdRepositories repos;

    public CicdRepositoryController(Current current, CicdRepositories repos) {
        this.current = current;
        this.repos = repos;
    }

    @GetMapping("/connections")
    public List<CicdRepositories.ConnectionChoice> connections(@PathVariable String project) {
        repos.manageable(current.ctx(), project);
        return repos.connections();
    }

    @GetMapping("/connections/{connectionId}/search")
    public List<Map<String, Object>> search(@PathVariable String project, @PathVariable UUID connectionId,
                                            @RequestParam(required = false) String q) {
        repos.manageable(current.ctx(), project);
        return repos.search(connectionId, q);
    }

    @GetMapping("/repositories")
    public List<CicdRepositories.RepositoryDto> list(@PathVariable String project) {
        return repos.list(repos.viewable(current.ctx(), project));
    }

    @PostMapping("/repositories")
    public ResponseEntity<CicdRepositories.RepositoryDto> attach(@PathVariable String project, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var p = repos.manageable(ctx, project);
        return ResponseEntity.status(HttpStatus.CREATED).body(repos.attach(ctx, p, in));
    }

    @DeleteMapping("/repositories/{repoId}")
    public ResponseEntity<Void> detach(@PathVariable String project, @PathVariable UUID repoId) {
        var ctx = current.ctx();
        repos.detach(ctx, repos.manageable(ctx, project), repoId);
        return ResponseEntity.noContent().build();
    }
}

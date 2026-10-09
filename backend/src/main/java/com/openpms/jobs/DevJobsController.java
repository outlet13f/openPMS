package com.openpms.jobs;

import com.openpms.common.ApiException;
import com.openpms.core.Current;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development only (dev profile): an admin runs a job now, optionally "as if" at another moment, so date alerts
 * and digests can be checked without waiting for the clock. Not present in production builds' default profile.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/v1/dev/jobs")
public class DevJobsController {
    private final Current current;
    private final JobCatalog catalog;

    public DevJobsController(Current current, JobCatalog catalog) {
        this.current = current;
        this.catalog = catalog;
    }

    public record Result(String job, Instant at, int count) {}

    @PostMapping("/{name}")
    public Result run(@PathVariable String name, @RequestParam(required = false) String at) throws Exception {
        if (!current.ctx().isAdmin()) throw ApiException.forbidden("관리자만 실행할 수 있습니다.");
        Instant when;
        try {
            when = at == null ? Instant.now() : Instant.parse(at);
        } catch (Exception e) {
            throw ApiException.field("at", "ISO-8601 시각(예: 2026-10-10T00:00:00Z)으로 입력하세요.");
        }
        var job = catalog.all().get(name);
        if (job == null) throw ApiException.notFound("그런 작업은 없습니다.");
        return new Result(name, when, job.run(when));
    }
}

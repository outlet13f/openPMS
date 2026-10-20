package com.openpms.cicd;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Verification state of work packages on a source environment: the current result (the newest verification), when the work
 * package's commits last came into the environment, and from those the board column and the release warning. Also the default
 * pair of environments a board compares.
 */
@Component
public class CicdVerificationStates {
    public record Current(UUID id, UUID workPackageId, String result, String reason, UUID verifiedBy, OffsetDateTime verifiedAt, OffsetDateTime basisAt) {}

    public record EnvironmentRow(UUID id, int position, boolean production, boolean hidden) {}

    /**
     * "The work package's commits" (design 3): the commits of its links and the merge commit of a linked merged merge request;
     * dismissed links do not count, nor do the separate commits inside a merge request. One (wp, commit_id) row per pair.
     * {@code where} narrows both branches; it may use {@code l} (work_package_cicd_links) and {@code c} (cicd_commits) and is
     * repeated, so its parameters are passed twice. Shared by the board's difference, {@link #latestIn} and the release records.
     */
    static String workPackageCommits(String where) {
        return """
                select l.work_package_id as wp, c.id as commit_id from work_package_cicd_links l join cicd_commits c on c.id = l.commit_id
                 where l.dismissed_at is null and (%1$s)
                union
                select l.work_package_id, c.id from work_package_cicd_links l join cicd_merge_requests m on m.id = l.merge_request_id
                  join cicd_commits c on c.repository_id = m.repository_id and c.sha = m.merge_commit_sha
                 where l.dismissed_at is null and m.state = 'merged' and (%1$s)""".formatted(where);
    }

    /** The newest commit time of each work package in the source environment, repositories attached to the environment's project only. */
    private static final String LATEST_IN = "with k as (" + workPackageCommits("l.work_package_id = any (?::uuid[])") + """
            )
            select k.wp, max(ec.added_at) from k
              join cicd_environment_commits ec on ec.commit_id = k.commit_id and ec.environment_id = ?::uuid
              join cicd_repositories r on r.id = ec.repository_id and r.detached_at is null
              join cicd_environments e on e.id = ec.environment_id and e.project_id = r.project_id
             group by k.wp""";

    private static final String CURRENT = """
            select distinct on (work_package_id) id, work_package_id, result, reason, verified_by, verified_at, basis_at
              from cicd_verifications where environment_id = ?::uuid and work_package_id = any (?::uuid[])
             order by work_package_id, verified_at desc, id desc""";

    private final DSLContext db;

    public CicdVerificationStates(DSLContext db) {
        this.db = db;
    }

    /** The newest verification (verified_at, then id) of each work package on the source environment. Absent: none yet. */
    public Map<UUID, Current> current(UUID sourceEnvironment, Collection<UUID> workPackages) {
        var out = new HashMap<UUID, Current>();
        if (workPackages.isEmpty()) return out;
        for (var r : db.fetch(CURRENT, sourceEnvironment, workPackages.toArray(UUID[]::new)))
            out.put(r.get(1, UUID.class), new Current(r.get(0, UUID.class), r.get(1, UUID.class), r.get(2, String.class), r.get(3, String.class),
                    r.get(4, UUID.class), r.get(5, OffsetDateTime.class), r.get(6, OffsetDateTime.class)));
        return out;
    }

    /**
     * When each work package's commits last came into the source environment (보완 4): the newest added_at of all its commits in
     * it now, not only those of a difference. Absent: none of its commits is there.
     */
    public Map<UUID, OffsetDateTime> latestIn(UUID sourceEnvironment, Collection<UUID> workPackages) {
        var out = new HashMap<UUID, OffsetDateTime>();
        if (workPackages.isEmpty()) return out;
        var ids = workPackages.toArray(UUID[]::new);
        for (var r : db.fetch(LATEST_IN, ids, ids, sourceEnvironment)) out.put(r.get(0, UUID.class), r.get(1, OffsetDateTime.class));
        return out;
    }

    /** The first production environment that is not hidden, by position; null when there is none. */
    public static UUID defaultTarget(List<EnvironmentRow> projectEnvironments) {
        return projectEnvironments.stream().filter(e -> e.production() && !e.hidden()).min(Comparator.comparingInt(EnvironmentRow::position))
                .map(EnvironmentRow::id).orElse(null);
    }

    /** The environment right before the target by position that is not hidden; null when there is none. */
    public static UUID defaultSource(List<EnvironmentRow> projectEnvironments, UUID target) {
        var t = projectEnvironments.stream().filter(e -> e.id().equals(target)).findFirst().orElse(null);
        if (t == null) return null;
        return projectEnvironments.stream().filter(e -> !e.hidden() && !e.id().equals(target) && e.position() < t.position())
                .max(Comparator.comparingInt(EnvironmentRow::position)).map(EnvironmentRow::id).orElse(null);
    }

    public UUID defaultTarget(UUID projectId) {
        return defaultTarget(rows("project_id = ?::uuid", projectId));
    }

    public UUID defaultSource(UUID targetEnvironment) {
        return defaultSource(rows("project_id = (select project_id from cicd_environments where id = ?::uuid)", targetEnvironment), targetEnvironment);
    }

    private List<EnvironmentRow> rows(String where, UUID id) {
        return db.fetch("select id, position, production, hidden from cicd_environments where " + where, id)
                .map(r -> new EnvironmentRow(r.get(0, UUID.class), r.get(1, Integer.class), r.get(2, Boolean.class), r.get(3, Boolean.class)));
    }

    /** The board column: waiting (no result), reverify (a commit came in after the result's basis), failed, passed. */
    public static String column(Current c, OffsetDateTime latestIn) {
        if (c == null) return "waiting";
        if (stale(c, latestIn)) return "reverify";
        return "failed".equals(c.result()) ? "failed" : "passed";
    }

    /** The release warning, in the same order as the columns: unverified, stale (even after a failure), failed, or null when passed. */
    public static String warning(Current c, OffsetDateTime latestIn) {
        if (c == null) return "unverified";
        if (stale(c, latestIn)) return "stale";
        return "failed".equals(c.result()) ? "failed" : null;
    }

    private static boolean stale(Current c, OffsetDateTime latestIn) {
        return latestIn != null && c.basisAt() != null && latestIn.toInstant().isAfter(c.basisAt().toInstant());
    }
}

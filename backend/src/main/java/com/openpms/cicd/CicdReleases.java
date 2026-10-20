package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_RELEASES;
import static com.openpms.db.Tables.CICD_RELEASE_ITEMS;

import com.openpms.api.Dto;
import com.openpms.cicd.CicdReleaseController.EnvironmentRef;
import com.openpms.cicd.CicdReleaseController.ReleaseDetail;
import com.openpms.cicd.CicdReleaseController.ReleaseItem;
import com.openpms.cicd.CicdReleaseController.ReleaseRow;
import com.openpms.cicd.CicdReleaseController.VerificationDto;
import com.openpms.common.ApiException;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot.ProjectRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

/**
 * Release records (design 4.4): a successful deployment to a production environment that brought commits in becomes one
 * read-only record of the work packages it carried, each with its verification on the default source environment at that
 * moment. Written inside the deployment processing's transaction (CicdDeployments.apply, giveUp, broken): no lock of its own,
 * no call to a code host. Numbers, subjects, verifications and warnings are copies; later changes do not touch a record.
 */
@Component
public class CicdReleases {
    /**
     * The work packages of the commits this deployment brought in (design 3: links not dismissed, and the merge commit of a
     * linked merged merge request), in the environment's project and its sub-projects (CicdLinker's tree).
     */
    private static final String CARRIED = """
            with recursive tree as (select id from projects where id = ?::uuid union select p.id from projects p join tree t on p.parent_id = t.id),
                 k as (""" + CicdVerificationStates.workPackageCommits(
                    "c.id in (select commit_id from cicd_deployment_commits where deployment_id = ?::uuid)") + """
            )
            select w.id, w.number, w.subject from work_packages w
             where w.id in (select wp from k) and w.project_id in (select id from tree)
             order by w.number""";

    private final DSLContext db;
    private final CicdVerificationStates states;

    public CicdReleases(DSLContext db, CicdVerificationStates states) {
        this.db = db;
        this.states = states;
    }

    /** The environment's project when it is a production environment, else null (no record). */
    private UUID productionProject(UUID environmentId) {
        if (environmentId == null) return null;
        var e = db.select(CICD_ENVIRONMENTS.PROJECT_ID, CICD_ENVIRONMENTS.PRODUCTION).from(CICD_ENVIRONMENTS)
                .where(CICD_ENVIRONMENTS.ID.eq(environmentId)).fetchOne();
        return e != null && Boolean.TRUE.equals(e.value2()) ? e.value1() : null;
    }

    /**
     * A deployment that brought commits into an environment: a record when the environment is a production one (once per
     * deployment). Called inside the apply transaction, only when something came in.
     */
    void record(UUID deploymentId, UUID environmentId, UUID repositoryId, OffsetDateTime deployedAt, boolean partial) {
        var project = productionProject(environmentId);
        if (project == null) return;
        var source = states.defaultSource(environmentId);
        var release = db.insertInto(CICD_RELEASES)
                .set(CICD_RELEASES.PROJECT_ID, project).set(CICD_RELEASES.ENVIRONMENT_ID, environmentId)
                .set(CICD_RELEASES.SOURCE_ENVIRONMENT_ID, source).set(CICD_RELEASES.DEPLOYMENT_ID, deploymentId)
                .set(CICD_RELEASES.REPOSITORY_ID, repositoryId).set(CICD_RELEASES.DEPLOYED_AT, deployedAt)
                .set(CICD_RELEASES.PARTIAL, partial)
                .onConflict(CICD_RELEASES.DEPLOYMENT_ID).doNothing()
                .returning(CICD_RELEASES.ID).fetchOne();
        if (release == null) return;   // already recorded
        var carried = db.fetch(CARRIED, project, deploymentId, deploymentId);
        if (carried.isEmpty()) return;
        var ids = carried.getValues(0, UUID.class);
        var current = source == null ? Map.<UUID, CicdVerificationStates.Current>of() : states.current(source, ids);
        var latest = source == null ? Map.<UUID, OffsetDateTime>of() : states.latestIn(source, ids);
        var insert = db.insertInto(CICD_RELEASE_ITEMS, CICD_RELEASE_ITEMS.RELEASE_ID, CICD_RELEASE_ITEMS.WORK_PACKAGE_ID, CICD_RELEASE_ITEMS.NUMBER,
                CICD_RELEASE_ITEMS.SUBJECT, CICD_RELEASE_ITEMS.VERIFICATION_ID, CICD_RELEASE_ITEMS.WARNING);
        for (var w : carried) {
            var wp = w.get(0, UUID.class);
            var c = current.get(wp);
            // without a source environment nothing can have been verified for this release
            var warning = source == null ? "unverified" : CicdVerificationStates.warning(c, latest.get(wp));
            insert = insert.values(release.getId(), wp, w.get(1, Long.class), w.get(2, String.class), c == null ? null : c.id(), warning);
        }
        insert.execute();
    }

    /**
     * A deployment whose content could not be found out (the compare gave up, or its processing kept failing) after the
     * environment already had a head: a record without work packages when the environment is a production one.
     */
    void contentFailed(UUID deploymentId, UUID environmentId, UUID repositoryId, OffsetDateTime deployedAt) {
        var project = productionProject(environmentId);
        if (project == null) return;
        db.insertInto(CICD_RELEASES)
                .set(CICD_RELEASES.PROJECT_ID, project).set(CICD_RELEASES.ENVIRONMENT_ID, environmentId)
                .set(CICD_RELEASES.SOURCE_ENVIRONMENT_ID, states.defaultSource(environmentId)).set(CICD_RELEASES.DEPLOYMENT_ID, deploymentId)
                .set(CICD_RELEASES.REPOSITORY_ID, repositoryId).set(CICD_RELEASES.DEPLOYED_AT, deployedAt)
                .set(CICD_RELEASES.CONTENT_FAILED, true)
                .onConflict(CICD_RELEASES.DEPLOYMENT_ID).doNothing().execute();
    }

    /* ---------- reading (the list and the detail) ---------- */

    private static final String RELEASES = """
            select r.id, r.deployed_at, r.partial, r.content_failed, e.id as env_id, e.name as env_name, e.position, e.production, e.hidden,
                   s.name as source_name, repo.full_path, d.web_url
              from cicd_releases r join cicd_environments e on e.id = r.environment_id
              join cicd_repositories repo on repo.id = r.repository_id join cicd_deployments d on d.id = r.deployment_id
              left join cicd_environments s on s.id = r.source_environment_id
             where r.project_id = ?::uuid %s
             order by r.deployed_at desc, r.id %s""";

    private static final String ITEMS = """
            select i.release_id, i.work_package_id, w.project_id, i.number, i.subject, i.warning,
                   v.id as v_id, v.result, v.reason, v.verified_by, v.verified_at, ve.name as v_env
              from cicd_release_items i left join work_packages w on w.id = i.work_package_id
              left join cicd_verifications v on v.id = i.verification_id left join cicd_environments ve on ve.id = v.environment_id
             where i.release_id = any (?::uuid[])
             order by i.number""";

    /** The item as the viewer may see it, or null: only the items of work packages the viewer may view (deleted ones count as hidden). */
    private static ReleaseItem visible(Ctx ctx, Record r) {
        var wp = r.get("work_package_id", UUID.class);
        var project = wp == null ? null : ctx.s().project(r.get("project_id", UUID.class)).orElse(null);
        if (project == null || !ctx.can(project, wp, "wp.view")) return null;
        var vid = r.get("v_id", UUID.class);
        var verification = vid == null ? null
                : new VerificationDto(vid, r.get("result", String.class), r.get("reason", String.class),
                        Refs.user(ctx.s(), r.get("verified_by", UUID.class)), r.get("verified_at", OffsetDateTime.class), r.get("v_env", String.class));
        return new ReleaseItem(wp, r.get("number", Long.class), r.get("subject", String.class), verification, r.get("warning", String.class));
    }

    private Map<UUID, List<Record>> itemRows(List<UUID> releaseIds) {
        var out = new HashMap<UUID, List<Record>>();
        if (releaseIds.isEmpty()) return out;
        for (var r : db.fetch(ITEMS, (Object) releaseIds.toArray(new UUID[0])))
            out.computeIfAbsent(r.get("release_id", UUID.class), k -> new ArrayList<>()).add(r);
        return out;
    }

    private static ReleaseRow row(Record r, List<ReleaseItem> shown, int total) {
        return new ReleaseRow(r.get("id", UUID.class), r.get("deployed_at", OffsetDateTime.class),
                new EnvironmentRef(r.get("env_id", UUID.class), r.get("env_name", String.class), r.get("position", Integer.class),
                        r.get("production", Boolean.class), r.get("hidden", Boolean.class)),
                r.get("source_name", String.class), r.get("full_path", String.class), r.get("web_url", String.class),
                r.get("partial", Boolean.class), r.get("content_failed", Boolean.class), shown.size(),
                (int) shown.stream().filter(i -> i.warning() != null).count(), total - shown.size());
    }

    /** The project's release records, newest first; itemCount and warningCount count what the viewer may see, hiddenCount the rest. */
    public Dto.Page<ReleaseRow> list(Ctx ctx, ProjectRow p, int page, int pageSize) {
        page = Math.max(1, page);
        pageSize = Math.min(100, Math.max(1, pageSize));
        long total = db.fetchOne("select count(*) from cicd_releases where project_id = ?::uuid", p.id()).get(0, Long.class);
        var rows = db.fetch(RELEASES.formatted("", "limit ? offset ?"), p.id(), pageSize, (long) (page - 1) * pageSize);
        var items = itemRows(rows.getValues("id", UUID.class));
        var out = new ArrayList<ReleaseRow>();
        for (var r : rows) {
            var all = items.getOrDefault(r.get("id", UUID.class), List.of());
            var shown = all.stream().map(i -> visible(ctx, i)).filter(i -> i != null).toList();
            out.add(row(r, shown, all.size()));
        }
        return new Dto.Page<>(out, total, page, pageSize);
    }

    public ReleaseDetail detail(Ctx ctx, ProjectRow p, UUID releaseId) {
        var r = db.fetchOne(RELEASES.formatted("and r.id = ?::uuid", ""), p.id(), releaseId);
        if (r == null) throw ApiException.notFound("릴리스 기록을 찾을 수 없습니다.");
        var all = itemRows(List.of(releaseId)).getOrDefault(releaseId, List.of());
        var shown = all.stream().map(i -> visible(ctx, i)).filter(i -> i != null).toList();
        return new ReleaseDetail(row(r, shown, all.size()), shown);
    }
}

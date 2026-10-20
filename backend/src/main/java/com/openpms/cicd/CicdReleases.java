package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_RELEASES;
import static com.openpms.db.Tables.CICD_RELEASE_ITEMS;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
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
}

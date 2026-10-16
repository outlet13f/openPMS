package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_PIPELINES;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * Stores build (pipeline) and deployment notices. The same state may come twice (webhook and poll) and states may come out of
 * order, so a stored row changes only for a newer notice (CicdStates.replaces); a successful deployment never changes again.
 * Moving an environment's state on from a deployment is the deployment advance's work, not this class's.
 */
@Service
public class CicdBuilds {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{7,64}");

    private final DSLContext db;
    private final CicdEnvironments environments;

    public CicdBuilds(DSLContext db, CicdEnvironments environments) {
        this.db = db;
        this.environments = environments;
    }

    /** source: the connection's kind. linkBase: the address a link must share its origin with. */
    void pipeline(CicdRepoRef repo, String source, String linkBase, CicdEvent.Pipeline p) {
        var url = CicdLinks.sameOrigin(p.webUrl(), linkBase);
        var name = p.name() == null ? "" : p.name();
        var ref = p.ref() == null ? "" : p.ref();
        int added = db.insertInto(CICD_PIPELINES)
                .set(CICD_PIPELINES.REPOSITORY_ID, repo.id()).set(CICD_PIPELINES.EXTERNAL_ID, p.externalId())
                .set(CICD_PIPELINES.NAME, name).set(CICD_PIPELINES.SHA, p.sha()).set(CICD_PIPELINES.REF, ref)
                .set(CICD_PIPELINES.STATUS, p.status()).set(CICD_PIPELINES.WEB_URL, url)
                .set(CICD_PIPELINES.STARTED_AT, p.startedAt()).set(CICD_PIPELINES.FINISHED_AT, p.finishedAt())
                .set(CICD_PIPELINES.SOURCE, source).set(CICD_PIPELINES.UPDATED_AT, p.updatedAt())
                .onConflict(CICD_PIPELINES.REPOSITORY_ID, CICD_PIPELINES.EXTERNAL_ID).doNothing().execute();
        if (added == 1) return;
        var old = db.selectFrom(CICD_PIPELINES).where(CICD_PIPELINES.REPOSITORY_ID.eq(repo.id()), CICD_PIPELINES.EXTERNAL_ID.eq(p.externalId()))
                .forUpdate().fetchOne();
        if (old == null || !CicdStates.replaces(old.getStatus(), old.getUpdatedAt(), p.status(), p.updatedAt())) return;
        // what a notice leaves out (no name, no link, no start time) keeps what an earlier one said
        db.update(CICD_PIPELINES)
                .set(CICD_PIPELINES.STATUS, p.status()).set(CICD_PIPELINES.UPDATED_AT, p.updatedAt()).set(CICD_PIPELINES.SHA, p.sha())
                .set(CICD_PIPELINES.NAME, name.isEmpty() ? old.getName() : name).set(CICD_PIPELINES.REF, ref.isEmpty() ? old.getRef() : ref)
                .set(CICD_PIPELINES.WEB_URL, url.isEmpty() ? old.getWebUrl() : url)
                .set(CICD_PIPELINES.STARTED_AT, p.startedAt() != null ? p.startedAt() : old.getStartedAt())
                .set(CICD_PIPELINES.FINISHED_AT, p.finishedAt())
                .where(CICD_PIPELINES.ID.eq(old.getId())).execute();
    }

    /** imageTagPattern: the connection's own rule, or null for the default. */
    void deployment(CicdRepoRef repo, String source, String linkBase, String imageTagPattern, CicdEvent.Deployment d) {
        var url = CicdLinks.sameOrigin(d.webUrl(), linkBase);
        var given = d.sha() == null ? "" : d.sha().toLowerCase(Locale.ROOT);
        var sha = SHA.matcher(given).matches() ? given : ImageTags.sha(d.image(), imageTagPattern).orElse(null);
        sha = resolve(repo.id(), sha);
        var image = d.image() == null || d.image().isEmpty() ? null : d.image();
        var environmentId = environments.match(repo.projectId(), d.environment());
        int added = db.insertInto(CICD_DEPLOYMENTS)
                .set(CICD_DEPLOYMENTS.REPOSITORY_ID, repo.id()).set(CICD_DEPLOYMENTS.EXTERNAL_ID, d.externalId())
                .set(CICD_DEPLOYMENTS.ENVIRONMENT_ID, environmentId).set(CICD_DEPLOYMENTS.ENVIRONMENT_NAME, d.environment())
                .set(CICD_DEPLOYMENTS.SHA, sha).set(CICD_DEPLOYMENTS.IMAGE, image).set(CICD_DEPLOYMENTS.STATUS, d.status())
                .set(CICD_DEPLOYMENTS.WEB_URL, url).set(CICD_DEPLOYMENTS.DEPLOYED_AT, d.deployedAt()).set(CICD_DEPLOYMENTS.SOURCE, source)
                .set(CICD_DEPLOYMENTS.INCLUSION, inclusion(d.status(), environmentId, sha))
                .onConflict(CICD_DEPLOYMENTS.REPOSITORY_ID, CICD_DEPLOYMENTS.EXTERNAL_ID).doNothing().execute();
        if (added == 1) return;
        var old = db.selectFrom(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.REPOSITORY_ID.eq(repo.id()), CICD_DEPLOYMENTS.EXTERNAL_ID.eq(d.externalId()))
                .forUpdate().fetchOne();
        // a successful deployment is never reopened: what the environment holds was decided from it
        if (old == null || "success".equals(old.getStatus())) return;
        if (!CicdStates.replaces(old.getStatus(), old.getDeployedAt(), d.status(), d.deployedAt())) return;
        db.update(CICD_DEPLOYMENTS)
                .set(CICD_DEPLOYMENTS.STATUS, d.status()).set(CICD_DEPLOYMENTS.DEPLOYED_AT, d.deployedAt()).set(CICD_DEPLOYMENTS.SHA, sha)
                .set(CICD_DEPLOYMENTS.IMAGE, image).set(CICD_DEPLOYMENTS.WEB_URL, url.isEmpty() ? old.getWebUrl() : url)
                .set(CICD_DEPLOYMENTS.INCLUSION, inclusion(d.status(), old.getEnvironmentId(), sha))
                .where(CICD_DEPLOYMENTS.ID.eq(old.getId())).execute();
    }

    /** Only a successful deployment to a known environment waits for the inclusion check; without a commit it is only recorded. */
    private static String inclusion(String status, UUID environmentId, String sha) {
        if (!"success".equals(status) || environmentId == null) return null;
        return sha == null ? "unknown_commit" : "checking";
    }

    /**
     * A short number is looked up among this repository's commits: one match gives the full number, two or more mean the
     * commit is unknown (null), none leaves the short number (the compare API resolves it).
     */
    private String resolve(UUID repositoryId, String sha) {
        if (sha == null || sha.length() >= 40) return sha;
        var found = db.select(CICD_COMMITS.SHA).from(CICD_COMMITS)
                .where(CICD_COMMITS.REPOSITORY_ID.eq(repositoryId), CICD_COMMITS.SHA.startsWith(sha)).limit(2).fetch(CICD_COMMITS.SHA);
        if (found.size() == 1) return found.get(0);
        return found.isEmpty() ? sha : null;
    }
}

package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_DEPLOYMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;
import static com.openpms.db.Tables.CICD_FIRST_DEPLOY_CHECKS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves each environment's state on from its successful deployments, one deployment at a time per repository and
 * environment, in the order they were deployed. The previous deployment of the environment (its head) is compared with the
 * new one both ways: what the new one has and the head did not came in, what the head had and the new one has not went out
 * (a rollback, or a branch that left the old line). The code host is asked outside any transaction; the result is written in
 * one transaction that locks the environment's head row and writes nothing if the head moved meanwhile (the next pass plans
 * again). No synchronized, no in-memory locks: the head row is the only lock. A code host that does not answer at all (refused,
 * dropped, timed out) is asked once per run: the deployment that hit it waits like any failed compare, and the connection's
 * other deployments are left as they are until the next run, so one dead server cannot hold the shared job for minutes.
 */
@Service
public class CicdDeployments {
    private static final Logger log = LoggerFactory.getLogger(CicdDeployments.class);
    /** At most this many commits are read each way; more makes the deployment "partial". */
    static final int MAX_PER_DIRECTION = 1000;
    /** The fifth failed compare gives up ("failed"). */
    static final int MAX_ATTEMPTS = 5;
    /** A first deployment checks at most this many linked commits (the newest); Task 11's check uses the same bound. */
    static final int FIRST_CANDIDATES = 200;
    /** Deployments taken per pass. */
    static final int BATCH = 100;
    /** BATCH, smaller in a test only (package-private, no setting). */
    int batch = BATCH;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{7,64}");

    /**
     * The front of each line: checking, not a head (a checking head is a first deployment being checked), nothing earlier waiting.
     * A detached repository's deployments wait untouched (no call to its host) and go on if it is attached again. The connections
     * whose host did not answer in this run are left out, so their deployments cannot fill the batch ahead of other connections'.
     */
    private static final String QUEUE = """
            select d.id from cicd_deployments d join cicd_repositories r on r.id = d.repository_id
             where d.inclusion = 'checking' and (d.next_attempt_at is null or d.next_attempt_at <= ?::timestamptz)
               and r.detached_at is null and r.connection_id <> all (?::uuid[])
               and not exists (select 1 from cicd_environment_heads h where h.deployment_id = d.id)
               and not exists (select 1 from cicd_deployments e
                                where e.repository_id = d.repository_id and e.environment_id = d.environment_id and e.inclusion = 'checking'
                                  and (e.deployed_at, e.id) < (d.deployed_at, d.id))
             order by d.deployed_at, d.id limit ?""";

    /**
     * A first deployment's candidates: this repository's commits with a link not dismissed, and merge commits of linked merged
     * MRs, counting only links to work packages in the repository's current project and its sub-projects (CicdLinker's tree).
     */
    private static final String CANDIDATES = """
            with recursive tree as (select id from projects where id = ?::uuid
                                    union select p.id from projects p join tree t on p.parent_id = t.id),
                 linked as (select l.commit_id, l.merge_request_id from work_package_cicd_links l
                              join work_packages w on w.id = l.work_package_id
                             where l.dismissed_at is null and w.project_id in (select id from tree))
            select c.id, c.committed_at from cicd_commits c
             where c.repository_id = ?::uuid and c.id is distinct from ?::uuid
               and (exists (select 1 from linked l where l.commit_id = c.id)
                    or exists (select 1 from cicd_merge_requests m join linked l on l.merge_request_id = m.id
                                where m.repository_id = c.repository_id and m.state = 'merged'
                                  and c.sha = m.merge_commit_sha))
             order by c.committed_at desc, c.id limit ?""";

    private final DSLContext db;
    private final TransactionTemplate tx;
    private final Adapters adapters;
    private final SecretBox box;
    private final CicdCommitStore commitStore;
    private final CicdReleases releases;

    public CicdDeployments(DSLContext db, TransactionTemplate tx, Adapters adapters, SecretBox box, CicdCommitStore commitStore,
            CicdReleases releases) {
        this.db = db;
        this.tx = tx;
        this.adapters = adapters;
        this.box = box;
        this.commitStore = commitStore;
        this.releases = releases;
    }

    /** One waiting deployment with what planning needs. */
    private record Waiting(UUID id, UUID repositoryId, UUID environmentId, String sha, OffsetDateTime deployedAt, int attempts,
            UUID projectId, UUID environmentProjectId, String webUrl, String externalId, UUID connectionId, String kind,
            String baseUrl, boolean active, String tokenEnc, boolean hidden) {}

    private record Head(String sha, UUID deploymentId, OffsetDateTime deployedAt) {}

    /** @return the number of deployments whose state changed */
    public int advance(Instant now) {
        return advance(now, new HashSet<>());
    }

    /**
     * @param unreachable connections whose host did not answer in this run (shared with the run's other passes and the
     *                    first-deployment check): their deployments are skipped, and a host found dead here is added
     * @return the number of deployments whose state changed
     */
    public int advance(Instant now, Set<UUID> unreachable) {
        int changed = 0;
        // a host found dead in this pass: take the batch again without its connection, so the deployments of other connections
        // behind a full batch of the dead one are reached in this run (ends: each new batch needs one more dead connection)
        for (int known = -1; known != unreachable.size(); ) {
            known = unreachable.size();
            changed += pass(db.fetch(QUEUE, now.atOffset(ZoneOffset.UTC), unreachable.toArray(UUID[]::new), batch).getValues(0, UUID.class),
                    now, unreachable);
        }
        return changed;
    }

    private int pass(List<UUID> ids, Instant now, Set<UUID> unreachable) {
        int changed = 0;
        for (var id : ids) {
            try {
                if (one(id, now, unreachable)) changed++;
            } catch (RuntimeException e) {
                // an error while applying (a constraint, the linker): it waits like a failed compare, so it cannot hold its line for good
                log.warn("cicd deployment {} not applied: {}: {}", id, e.getClass().getSimpleName(), firstLine(e.getMessage()));
                try {
                    if (broken(id, now)) changed++;
                } catch (RuntimeException again) {
                    log.warn("cicd deployment {} could not be put back: {}", id, again.getClass().getSimpleName());
                }
            }
        }
        return changed;
    }

    private boolean one(UUID id, Instant now, Set<UUID> unreachable) {
        var d = waiting(id);
        if (d == null) return false;   // done by another run meanwhile
        // the repository moved to another project since: the deployment belongs to the old project's environment, only recorded
        if (d.environmentProjectId() == null || !d.environmentProjectId().equals(d.projectId()) || d.sha() == null) return recordOnly(id);
        if (d.hidden()) return recordOnly(id);   // hidden since it was stored: like a success on a hidden environment (only recorded)
        var head = head(d.repositoryId(), d.environmentId());
        if (head == null) return startFirst(d);
        if (head.deploymentId().equals(d.id())) return false;
        if (d.deployedAt().isBefore(head.deployedAt())) return recordOnly(id);   // a late, older deployment
        if (head.sha().startsWith(d.sha()) || d.sha().startsWith(head.sha()))   // a restart of the same commit
            return apply(d, head, List.of(), List.of(), false);
        if (!d.active() || d.tokenEnc() == null || d.tokenEnc().isEmpty()) return giveUp(d, head, "no_token", d.attempts());
        if (unreachable.contains(d.connectionId())) return false;   // the host did not answer in this run: left as it is for the next
        CicdAdapter.Comparison added, removed;
        try {
            var remote = new CicdAdapter.Remote(d.baseUrl(), box.open(d.tokenEnc()));
            var adapter = adapters.of(d.kind());
            added = adapter.compare(remote, d.externalId(), head.sha(), d.sha(), MAX_PER_DIRECTION);
            removed = adapter.compare(remote, d.externalId(), d.sha(), head.sha(), MAX_PER_DIRECTION);
        } catch (IOException | RuntimeException e) {
            if (CicdPoller.unreachable(e) && unreachable.add(d.connectionId()))
                log.warn("cicd deployment {} connection {} unreachable ({}), its other deployments wait for the next run", d.id(), d.connectionId(),
                        errorCode(e));
            return failed(d, head, e, now);
        }
        return apply(d, head, added.commits(), removed.commits(), added.truncated() || removed.truncated());
    }

    private Waiting waiting(UUID id) {
        var r = db.select(CICD_DEPLOYMENTS.ID, CICD_DEPLOYMENTS.REPOSITORY_ID, CICD_DEPLOYMENTS.ENVIRONMENT_ID, CICD_DEPLOYMENTS.SHA,
                        CICD_DEPLOYMENTS.DEPLOYED_AT, CICD_DEPLOYMENTS.ATTEMPTS, CICD_REPOSITORIES.PROJECT_ID, CICD_ENVIRONMENTS.PROJECT_ID,
                        CICD_REPOSITORIES.WEB_URL, CICD_REPOSITORIES.EXTERNAL_ID, CICD_CONNECTIONS.ID, CICD_CONNECTIONS.KIND,
                        CICD_CONNECTIONS.BASE_URL, CICD_CONNECTIONS.ACTIVE, CICD_CONNECTIONS.API_TOKEN_ENC, CICD_ENVIRONMENTS.HIDDEN)
                .from(CICD_DEPLOYMENTS)
                .join(CICD_REPOSITORIES).on(CICD_REPOSITORIES.ID.eq(CICD_DEPLOYMENTS.REPOSITORY_ID))
                .join(CICD_CONNECTIONS).on(CICD_CONNECTIONS.ID.eq(CICD_REPOSITORIES.CONNECTION_ID))
                .leftJoin(CICD_ENVIRONMENTS).on(CICD_ENVIRONMENTS.ID.eq(CICD_DEPLOYMENTS.ENVIRONMENT_ID))
                .where(CICD_DEPLOYMENTS.ID.eq(id), CICD_DEPLOYMENTS.INCLUSION.eq("checking")).fetchOne();
        if (r == null) return null;
        return new Waiting(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(), r.value9(),
                r.value10(), r.value11(), r.value12(), r.value13(), Boolean.TRUE.equals(r.value14()), r.value15(), Boolean.TRUE.equals(r.value16()));
    }

    private Head head(UUID repositoryId, UUID environmentId) {
        return db.select(CICD_ENVIRONMENT_HEADS.SHA, CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID, CICD_ENVIRONMENT_HEADS.DEPLOYED_AT)
                .from(CICD_ENVIRONMENT_HEADS)
                .where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(repositoryId), CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID.eq(environmentId))
                .fetchOne(r -> new Head(r.value1(), r.value2(), r.value3()));
    }

    /** Locks the head row; true when it still names the deployment read while planning. */
    private boolean headUnchanged(Waiting d, Head planned) {
        var now = db.select(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID).from(CICD_ENVIRONMENT_HEADS)
                .where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(d.repositoryId()), CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID.eq(d.environmentId()))
                .forUpdate().fetchOne(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID);
        return now != null && now.equals(planned.deploymentId());
    }

    /** Locks the deployment row; true when it is still waiting (no other run finished it). */
    private boolean stillChecking(UUID id) {
        return db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(id), CICD_DEPLOYMENTS.INCLUSION.eq("checking"))
                .forUpdate().fetchOne() != null;
    }

    private boolean recordOnly(UUID id) {
        return db.update(CICD_DEPLOYMENTS).setNull(CICD_DEPLOYMENTS.INCLUSION)
                .where(CICD_DEPLOYMENTS.ID.eq(id), CICD_DEPLOYMENTS.INCLUSION.eq("checking")).execute() == 1;
    }

    /**
     * The environment's first deployment from this repository: it becomes the head, its own commit is in, and the linked commits
     * it may contain are listed for the first-deployment check. Without any, it is "first" at once.
     */
    private boolean startFirst(Waiting d) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            // the repository may have moved since planning: the share lock waits for a move, then reads its new project
            var project = db.select(CICD_REPOSITORIES.PROJECT_ID).from(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(d.repositoryId()))
                    .forShare().fetchOne(CICD_REPOSITORIES.PROJECT_ID);
            if (project == null || !db.fetchExists(CICD_ENVIRONMENTS, CICD_ENVIRONMENTS.ID.eq(d.environmentId()),
                    CICD_ENVIRONMENTS.PROJECT_ID.eq(project), CICD_ENVIRONMENTS.HIDDEN.isFalse()))
                return recordOnly(d.id());
            int made = db.insertInto(CICD_ENVIRONMENT_HEADS)
                    .set(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID, d.repositoryId()).set(CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID, d.environmentId())
                    .set(CICD_ENVIRONMENT_HEADS.SHA, d.sha()).set(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID, d.id())
                    .set(CICD_ENVIRONMENT_HEADS.DEPLOYED_AT, d.deployedAt())
                    .onConflictDoNothing().execute();
            if (made == 0) return false;   // another run started this environment first
            if (!stillChecking(d.id())) {
                st.setRollbackOnly();
                return false;
            }
            var self = commitId(d.repositoryId(), d.sha());
            if (self != null) bringIn(d, self);
            var candidates = db.fetch(CANDIDATES, project, d.repositoryId(), self, FIRST_CANDIDATES);
            OffsetDateTime cutoff = null;
            for (var c : candidates) {
                db.insertInto(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID, d.id())
                        .set(CICD_FIRST_DEPLOY_CHECKS.COMMIT_ID, c.get(0, UUID.class)).onConflictDoNothing().execute();
                cutoff = c.get(1, OffsetDateTime.class);   // newest first: the last one is the oldest checked
            }
            db.update(CICD_DEPLOYMENTS)
                    .set(CICD_DEPLOYMENTS.INCLUSION, candidates.isEmpty() ? "first" : "checking")
                    .set(CICD_DEPLOYMENTS.FIRST_CUTOFF_AT, candidates.size() == FIRST_CANDIDATES ? cutoff : null)
                    .set(CICD_DEPLOYMENTS.ATTEMPTS, 0).setNull(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT).setNull(CICD_DEPLOYMENTS.CHECK_ERROR)
                    .where(CICD_DEPLOYMENTS.ID.eq(d.id())).execute();
            return true;
        }));
    }

    /** Writes an already-fetched comparison: added commits in, removed commits out, the head on to this deployment. */
    private boolean apply(Waiting d, Head planned, List<CicdEvent.Commit> added, List<CicdEvent.Commit> removed, boolean truncated) {
        var done = Boolean.TRUE.equals(tx.execute(st -> {
            if (!headUnchanged(d, planned) || !stillChecking(d.id())) return false;   // planned again on the next pass
            var repo = new CicdRepoRef(d.repositoryId(), d.projectId(), d.webUrl());
            int brought = 0;
            for (var c : added) {
                if (c.sha() == null || !SHA.matcher(c.sha()).matches() || c.committedAt() == null) continue;   // nothing we could store
                bringIn(d, commitStore.upsert(repo, c));
                brought++;
            }
            for (var c : removed) {
                if (c.sha() == null) continue;
                db.deleteFrom(CICD_ENVIRONMENT_COMMITS)
                        .where(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID.eq(d.environmentId()),
                                CICD_ENVIRONMENT_COMMITS.COMMIT_ID.eq(DSL.select(CICD_COMMITS.ID).from(CICD_COMMITS)
                                        .where(CICD_COMMITS.REPOSITORY_ID.eq(d.repositoryId()), CICD_COMMITS.SHA.eq(c.sha()))))
                        .execute();
            }
            moveHead(d);
            db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.INCLUSION, truncated ? "partial" : "compared")
                    .set(CICD_DEPLOYMENTS.ATTEMPTS, 0).setNull(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT).setNull(CICD_DEPLOYMENTS.CHECK_ERROR)
                    .where(CICD_DEPLOYMENTS.ID.eq(d.id())).execute();
            // a release record only when commits came in: not a restart of the same commit, nor a rollback that only took some out
            if (brought > 0) releases.record(d.id(), d.environmentId(), d.repositoryId(), d.deployedAt(), truncated);
            return true;
        }));
        if (done && truncated) log.warn("cicd deployment {} partial: more than {} commits one way", d.id(), MAX_PER_DIRECTION);
        return done;
    }

    /** A compare that failed: try again later (1, 2, 4, 8 minutes), or give up on the fifth failure or a reply too large to read. */
    private boolean failed(Waiting d, Head planned, Exception e, Instant now) {
        var code = errorCode(e);
        int attempts = d.attempts() + 1;
        if (code.equals("too_large") || attempts >= MAX_ATTEMPTS) return giveUp(d, planned, code, attempts);
        db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.ATTEMPTS, attempts)
                .set(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT, now.plus(retryDelay(attempts)).atOffset(ZoneOffset.UTC))
                .set(CICD_DEPLOYMENTS.CHECK_ERROR, code)
                .where(CICD_DEPLOYMENTS.ID.eq(d.id()), CICD_DEPLOYMENTS.INCLUSION.eq("checking")).execute();
        return false;
    }

    /**
     * A deployment whose handling threw: one more attempt, retried 1, 2, 4, 8 minutes later; the fifth gives up with "error"
     * and, like any "failed", moves the head on (or makes it the head) so the next deployment of the environment can go.
     */
    private boolean broken(UUID id, Instant now) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            var d = db.select(CICD_DEPLOYMENTS.REPOSITORY_ID, CICD_DEPLOYMENTS.ENVIRONMENT_ID, CICD_DEPLOYMENTS.ATTEMPTS)
                    .from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(id), CICD_DEPLOYMENTS.INCLUSION.eq("checking")).fetchOne();
            if (d == null) return false;
            // the same lock order as everywhere else: the head row, then the deployment row
            var head = db.select(CICD_ENVIRONMENT_HEADS.DEPLOYED_AT).from(CICD_ENVIRONMENT_HEADS)
                    .where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(d.value1()), CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID.eq(d.value2()))
                    .forUpdate().fetchOne();
            var row = db.selectFrom(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ID.eq(id), CICD_DEPLOYMENTS.INCLUSION.eq("checking"))
                    .forUpdate().fetchOne();
            if (row == null) return false;
            int attempts = row.getAttempts() + 1;
            if (attempts < MAX_ATTEMPTS) {
                db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.ATTEMPTS, attempts)
                        .set(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT, now.plus(retryDelay(attempts)).atOffset(ZoneOffset.UTC))
                        .set(CICD_DEPLOYMENTS.CHECK_ERROR, "error").where(CICD_DEPLOYMENTS.ID.eq(id)).execute();
                return false;
            }
            boolean moved = row.getSha() != null && row.getEnvironmentId() != null && (head == null || !row.getDeployedAt().isBefore(head.value1()));
            if (moved) {
                db.insertInto(CICD_ENVIRONMENT_HEADS)
                        .set(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID, row.getRepositoryId()).set(CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID, row.getEnvironmentId())
                        .set(CICD_ENVIRONMENT_HEADS.SHA, row.getSha()).set(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID, id)
                        .set(CICD_ENVIRONMENT_HEADS.DEPLOYED_AT, row.getDeployedAt())
                        .onConflict(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID, CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID).doUpdate()
                        .set(CICD_ENVIRONMENT_HEADS.SHA, row.getSha()).set(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID, id)
                        .set(CICD_ENVIRONMENT_HEADS.DEPLOYED_AT, row.getDeployedAt()).execute();
            }
            db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.INCLUSION, "failed").set(CICD_DEPLOYMENTS.ATTEMPTS, attempts)
                    .setNull(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT).set(CICD_DEPLOYMENTS.CHECK_ERROR, "error").where(CICD_DEPLOYMENTS.ID.eq(id)).execute();
            // what came in is unknown: a "content failed" record, but not for the environment's first deployment (no head before)
            if (moved && head != null) releases.contentFailed(id, row.getEnvironmentId(), row.getRepositoryId(), row.getDeployedAt());
            log.warn("cicd deployment {} repository {}: gave up after {} errors", id, row.getRepositoryId(), attempts);
            return true;
        }));
    }

    /** The first line of a message, at most 200 characters (database messages; the adapters' messages are fixed text). */
    private static String firstLine(String m) {
        if (m == null) return "";
        var line = m.lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    /** "failed": the environment keeps its commits, but the head moves on so that the next deployment compares against this one. */
    private boolean giveUp(Waiting d, Head planned, String code, int attempts) {
        var done = Boolean.TRUE.equals(tx.execute(st -> {
            if (!headUnchanged(d, planned) || !stillChecking(d.id())) return false;
            moveHead(d);
            db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.INCLUSION, "failed").set(CICD_DEPLOYMENTS.ATTEMPTS, attempts)
                    .setNull(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT).set(CICD_DEPLOYMENTS.CHECK_ERROR, code)
                    .where(CICD_DEPLOYMENTS.ID.eq(d.id())).execute();
            releases.contentFailed(d.id(), d.environmentId(), d.repositoryId(), d.deployedAt());   // what came in is unknown
            return true;
        }));
        if (done) log.warn("cicd deployment {} repository {} connection {}: compare failed ({})", d.id(), d.repositoryId(), d.connectionId(), code);
        return done;
    }

    private void moveHead(Waiting d) {
        db.update(CICD_ENVIRONMENT_HEADS).set(CICD_ENVIRONMENT_HEADS.SHA, d.sha()).set(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID, d.id())
                .set(CICD_ENVIRONMENT_HEADS.DEPLOYED_AT, d.deployedAt())
                .where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(d.repositoryId()), CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID.eq(d.environmentId()))
                .execute();
    }

    /** The commit came in with this deployment: it is the deployment's, and the environment holds it from this deployment on. */
    private void bringIn(Waiting d, UUID commitId) {
        db.insertInto(CICD_DEPLOYMENT_COMMITS).set(CICD_DEPLOYMENT_COMMITS.DEPLOYMENT_ID, d.id()).set(CICD_DEPLOYMENT_COMMITS.COMMIT_ID, commitId)
                .onConflictDoNothing().execute();
        db.insertInto(CICD_ENVIRONMENT_COMMITS)
                .set(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID, d.environmentId()).set(CICD_ENVIRONMENT_COMMITS.REPOSITORY_ID, d.repositoryId())
                .set(CICD_ENVIRONMENT_COMMITS.COMMIT_ID, commitId).set(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID, d.id())
                .onConflict(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID, CICD_ENVIRONMENT_COMMITS.COMMIT_ID).doUpdate()
                .set(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID, DSL.excluded(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID))
                .execute();
    }

    /** The stored commit with this number (a short number only when exactly one commit starts with it), or null. */
    private UUID commitId(UUID repositoryId, String sha) {
        var found = db.select(CICD_COMMITS.ID).from(CICD_COMMITS)
                .where(CICD_COMMITS.REPOSITORY_ID.eq(repositoryId), sha.length() >= 40 ? CICD_COMMITS.SHA.eq(sha) : CICD_COMMITS.SHA.startsWith(sha))
                .limit(2).fetch(CICD_COMMITS.ID);
        return found.size() == 1 ? found.get(0) : null;
    }

    /** The wait before the next try after this many failures: 1, 2, 4, 8 minutes. */
    static Duration retryDelay(int attempts) {
        int n = Math.max(1, Math.min(attempts, MAX_ATTEMPTS - 1));
        return Duration.ofMinutes(1L << (n - 1));
    }

    /** A short, stored reason: "status 401", "timeout", "too_large" or "failed" (never a URL, query or token). */
    static String errorCode(Exception e) {
        if (e instanceof UnexpectedStatus s) return "status " + s.status();
        var m = e instanceof IOException ? e.getMessage() : null;
        if (m == null) return "failed";
        if (m.startsWith("unexpected status ")) return "status " + m.substring("unexpected status ".length()).strip();
        return switch (m) {
            case "request timed out" -> "timeout";
            case "response too large" -> "too_large";
            default -> "failed";
        };
    }
}

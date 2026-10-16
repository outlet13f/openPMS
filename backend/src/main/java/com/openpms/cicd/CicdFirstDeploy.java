package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_DEPLOYMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_COMMITS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;
import static com.openpms.db.Tables.CICD_FIRST_DEPLOY_CHECKS;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first-deployment check: an environment's first deployment from a repository has nothing to compare with, so each linked
 * commit it listed (CicdDeployments.startFirst, at most 200) is asked of the code host: is it the deployed commit or one of its
 * ancestors? At most CALLS_PER_REPO_PER_RUN questions per repository per run; a failed question is asked again 1, 2, 4, 8
 * minutes later and the fifth failure makes it "error". When no question is left the deployment is "first", or "failed" if any
 * answer was an error, and the next deployment of the environment can go. The host is asked outside any transaction; every
 * answer is written in its own transaction that locks the head row, then the deployment row (the order CicdDeployments uses).
 * A first deployment already being checked keeps being checked even if its environment is hidden meanwhile (it is already the
 * environment's head; stopping would hold the environment's later deployments). A detached repository's questions wait
 * untouched, and a code host that does not answer at all is asked once per run (its other questions wait for the next run).
 */
@Service
public class CicdFirstDeploy {
    private static final Logger log = LoggerFactory.getLogger(CicdFirstDeploy.class);
    /** Questions to the code host per repository in one run. */
    static final int CALLS_PER_REPO_PER_RUN = 50;
    /** The fifth failed question gives up on that commit ("error"). */
    static final int MAX_ATTEMPTS = 5;

    /**
     * The due open questions of first deployments being checked (heads still "checking") of attached repositories, oldest
     * deployment first and its newest commits first, at most CALLS_PER_REPO_PER_RUN per repository.
     */
    private static final String OPEN = """
            select x.deployment_id, x.commit_id, x.attempts, x.commit_sha, x.deployed_sha, x.repository_id, x.environment_id,
                   x.external_id, x.kind, x.base_url, x.active, x.api_token_enc, x.connection_id
              from (select q.deployment_id, q.commit_id, q.attempts, c.sha as commit_sha, d.sha as deployed_sha, d.repository_id,
                           d.environment_id, r.external_id, k.kind, k.base_url, k.active, k.api_token_enc, k.id as connection_id,
                           d.deployed_at, c.committed_at,
                           row_number() over (partition by d.repository_id order by d.deployed_at, d.id, c.committed_at desc, c.id) as n
                      from cicd_first_deploy_checks q
                      join cicd_deployments d on d.id = q.deployment_id
                      join cicd_environment_heads h on h.deployment_id = d.id
                      join cicd_commits c on c.id = q.commit_id
                      join cicd_repositories r on r.id = d.repository_id
                      join cicd_connections k on k.id = r.connection_id
                     where q.result is null and d.inclusion = 'checking' and d.sha is not null and r.detached_at is null
                       and (q.next_attempt_at is null or q.next_attempt_at <= ?::timestamptz)) x
             where x.n <= ?
             order by x.deployed_at, x.deployment_id, x.committed_at desc, x.commit_id""";

    /** First deployments being checked with no question left (all answered, or their commits are gone). */
    private static final String FINISHED = """
            select d.id from cicd_deployments d join cicd_environment_heads h on h.deployment_id = d.id
             where d.inclusion = 'checking'
               and not exists (select 1 from cicd_first_deploy_checks q where q.deployment_id = d.id and q.result is null)
             order by d.deployed_at, d.id limit 100""";

    private final DSLContext db;
    private final TransactionTemplate tx;
    private final Adapters adapters;
    private final SecretBox box;

    public CicdFirstDeploy(DSLContext db, TransactionTemplate tx, Adapters adapters, SecretBox box) {
        this.db = db;
        this.tx = tx;
        this.adapters = adapters;
        this.box = box;
    }

    private record Question(UUID deploymentId, UUID commitId, int attempts, String commitSha, String deployedSha, UUID repositoryId,
            UUID environmentId, String externalId, String kind, String baseUrl, boolean active, String tokenEnc, UUID connectionId) {}

    private enum Answer { IN, OUT, FAILED }

    /** @return the number of questions whose result was decided in this run */
    public int check(Instant now) {
        return check(now, new HashSet<>());
    }

    /**
     * @param unreachable connections whose host did not answer in this run (shared with the run's deployment passes): their
     *                    questions are skipped, and a host found dead here is added
     * @return the number of questions whose result was decided in this run
     */
    public int check(Instant now, Set<UUID> unreachable) {
        var questions = db.fetch(OPEN, now.atOffset(ZoneOffset.UTC), CALLS_PER_REPO_PER_RUN).map(r -> new Question(
                r.get(0, UUID.class), r.get(1, UUID.class), r.get(2, Integer.class), r.get(3, String.class), r.get(4, String.class),
                r.get(5, UUID.class), r.get(6, UUID.class), r.get(7, String.class), r.get(8, String.class), r.get(9, String.class),
                Boolean.TRUE.equals(r.get(10, Boolean.class)), r.get(11, String.class), r.get(12, UUID.class)));
        var calls = new HashMap<UUID, Integer>();   // per repository, this run only: a local map, no lock
        var noToken = new HashSet<UUID>();
        int decided = 0;
        for (var q : questions) {
            try {
                if (!q.active() || q.tokenEnc() == null || q.tokenEnc().isEmpty()) {
                    if (noToken.add(q.deploymentId())) decided += giveUp(q);
                    continue;
                }
                if (unreachable.contains(q.connectionId())) continue;   // the host did not answer in this run: left for the next
                int used = calls.getOrDefault(q.repositoryId(), 0);
                if (used >= CALLS_PER_REPO_PER_RUN) continue;
                calls.put(q.repositoryId(), used + 1);
                Answer answer;
                try {
                    var remote = new CicdAdapter.Remote(q.baseUrl(), box.open(q.tokenEnc()));
                    answer = adapters.of(q.kind()).contains(remote, q.externalId(), q.deployedSha(), q.commitSha()) ? Answer.IN : Answer.OUT;
                } catch (IOException | RuntimeException e) {
                    log.warn("cicd first deployment {} repository {}: check failed ({})", q.deploymentId(), q.repositoryId(),
                            CicdDeployments.errorCode(e));
                    if (CicdPoller.unreachable(e) && unreachable.add(q.connectionId()))
                        log.warn("cicd first deployment check: connection {} unreachable, its other questions wait for the next run", q.connectionId());
                    answer = Answer.FAILED;
                }
                if (write(q, answer, now)) decided++;
            } catch (RuntimeException e) {
                log.warn("cicd first deployment {} not checked: {}", q.deploymentId(), e.getClass().getSimpleName());
            }
        }
        for (var id : db.fetch(FINISHED).getValues(0, UUID.class)) {
            try {
                finish(id);
            } catch (RuntimeException e) {
                log.warn("cicd first deployment {} not finished: {}", id, e.getClass().getSimpleName());
            }
        }
        return decided;
    }

    /** Locks the head row, then the deployment row; true when this deployment is still the head and still being checked. */
    private boolean lockHead(UUID repositoryId, UUID environmentId, UUID deploymentId) {
        var head = db.select(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID).from(CICD_ENVIRONMENT_HEADS)
                .where(CICD_ENVIRONMENT_HEADS.REPOSITORY_ID.eq(repositoryId), CICD_ENVIRONMENT_HEADS.ENVIRONMENT_ID.eq(environmentId))
                .forUpdate().fetchOne(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID);
        var checking = db.select(CICD_DEPLOYMENTS.ID).from(CICD_DEPLOYMENTS)
                .where(CICD_DEPLOYMENTS.ID.eq(deploymentId), CICD_DEPLOYMENTS.INCLUSION.eq("checking")).forUpdate().fetchOne() != null;
        return checking && deploymentId.equals(head);
    }

    /** One answer: in (the commit joins the deployment and the environment), out, or one more failure. */
    private boolean write(Question q, Answer answer, Instant now) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            boolean head = lockHead(q.repositoryId(), q.environmentId(), q.deploymentId());
            var open = CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(q.deploymentId()).and(CICD_FIRST_DEPLOY_CHECKS.COMMIT_ID.eq(q.commitId()))
                    .and(CICD_FIRST_DEPLOY_CHECKS.RESULT.isNull());
            var row = db.selectFrom(CICD_FIRST_DEPLOY_CHECKS).where(open).forUpdate().fetchOne();
            if (row == null) return false;   // answered by another run meanwhile
            if (!head) {
                // the head moved on without this deployment (the queue should prevent it): the answer belongs to no head
                db.update(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.RESULT, "error").where(open).execute();
                return true;
            }
            switch (answer) {
                case IN -> {
                    db.insertInto(CICD_DEPLOYMENT_COMMITS).set(CICD_DEPLOYMENT_COMMITS.DEPLOYMENT_ID, q.deploymentId())
                            .set(CICD_DEPLOYMENT_COMMITS.COMMIT_ID, q.commitId()).onConflictDoNothing().execute();
                    db.insertInto(CICD_ENVIRONMENT_COMMITS)
                            .set(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID, q.environmentId()).set(CICD_ENVIRONMENT_COMMITS.REPOSITORY_ID, q.repositoryId())
                            .set(CICD_ENVIRONMENT_COMMITS.COMMIT_ID, q.commitId()).set(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID, q.deploymentId())
                            .onConflict(CICD_ENVIRONMENT_COMMITS.ENVIRONMENT_ID, CICD_ENVIRONMENT_COMMITS.COMMIT_ID).doUpdate()
                            .set(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID, DSL.excluded(CICD_ENVIRONMENT_COMMITS.DEPLOYMENT_ID)).execute();
                    db.update(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.RESULT, "in").where(open).execute();
                    return true;
                }
                case OUT -> {
                    db.update(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.RESULT, "out").where(open).execute();
                    return true;
                }
                default -> {
                    int attempts = row.getAttempts() + 1;
                    if (attempts >= MAX_ATTEMPTS) {
                        db.update(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.RESULT, "error")
                                .set(CICD_FIRST_DEPLOY_CHECKS.ATTEMPTS, attempts).setNull(CICD_FIRST_DEPLOY_CHECKS.NEXT_ATTEMPT_AT).where(open).execute();
                        return true;
                    }
                    db.update(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.ATTEMPTS, attempts)
                            .set(CICD_FIRST_DEPLOY_CHECKS.NEXT_ATTEMPT_AT, now.plus(CicdDeployments.retryDelay(attempts)).atOffset(ZoneOffset.UTC))
                            .where(open).execute();
                    return false;
                }
            }
        }));
    }

    /** No token or the connection is off: nothing can be asked, so every open question is an error and the deployment "failed". */
    private int giveUp(Question q) {
        var n = tx.execute(st -> {
            if (!lockHead(q.repositoryId(), q.environmentId(), q.deploymentId())) return 0;
            int errors = db.update(CICD_FIRST_DEPLOY_CHECKS).set(CICD_FIRST_DEPLOY_CHECKS.RESULT, "error").setNull(CICD_FIRST_DEPLOY_CHECKS.NEXT_ATTEMPT_AT)
                    .where(CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(q.deploymentId()), CICD_FIRST_DEPLOY_CHECKS.RESULT.isNull()).execute();
            db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.INCLUSION, "failed").set(CICD_DEPLOYMENTS.CHECK_ERROR, "no_token")
                    .setNull(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT).where(CICD_DEPLOYMENTS.ID.eq(q.deploymentId())).execute();
            return errors;
        });
        if (n == null || n == 0) return 0;
        log.warn("cicd first deployment {} repository {}: check failed (no_token)", q.deploymentId(), q.repositoryId());
        return n;
    }

    /** Every question answered: "first", or "failed" when any answer was an error (the commits found in stay in). */
    private void finish(UUID id) {
        tx.execute(st -> {
            var d = db.select(CICD_DEPLOYMENTS.REPOSITORY_ID, CICD_DEPLOYMENTS.ENVIRONMENT_ID).from(CICD_DEPLOYMENTS)
                    .where(CICD_DEPLOYMENTS.ID.eq(id), CICD_DEPLOYMENTS.INCLUSION.eq("checking")).fetchOne();
            if (d == null || d.value2() == null || !lockHead(d.value1(), d.value2(), id)) return null;
            if (db.fetchExists(CICD_FIRST_DEPLOY_CHECKS, CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(id), CICD_FIRST_DEPLOY_CHECKS.RESULT.isNull()))
                return null;
            boolean errors = db.fetchExists(CICD_FIRST_DEPLOY_CHECKS, CICD_FIRST_DEPLOY_CHECKS.DEPLOYMENT_ID.eq(id), CICD_FIRST_DEPLOY_CHECKS.RESULT.eq("error"));
            var update = db.update(CICD_DEPLOYMENTS).set(CICD_DEPLOYMENTS.INCLUSION, errors ? "failed" : "first")
                    .set(CICD_DEPLOYMENTS.ATTEMPTS, 0).setNull(CICD_DEPLOYMENTS.NEXT_ATTEMPT_AT);
            (errors ? update.set(CICD_DEPLOYMENTS.CHECK_ERROR, "first_check") : update.setNull(CICD_DEPLOYMENTS.CHECK_ERROR))
                    .where(CICD_DEPLOYMENTS.ID.eq(id)).execute();
            if (errors) log.warn("cicd first deployment {} repository {}: some commits could not be checked", id, d.value1());
            return null;
        });
    }
}

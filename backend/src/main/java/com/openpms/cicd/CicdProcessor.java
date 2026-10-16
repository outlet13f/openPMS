package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_CONNECTION_PROJECTS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job "cicd-process": turns the pending rows of cicd_events into commits, merge requests, builds, deployments and links to
 * work packages. Each event runs in its own transaction, so one broken event only marks itself failed.
 */
@Component
public class CicdProcessor {
    private static final Logger log = LoggerFactory.getLogger(CicdProcessor.class);
    static final int BATCH = 200;
    /** Upper bound of events per run, so one run cannot go on forever under a steady flow. */
    private static final int MAX_PER_RUN = 5000;
    /** The kinds this processor handles (a kind without a handler would stay pending rather than fail). */
    static final java.util.List<String> HANDLED_KINDS = java.util.List.of("commit", "merge_request", "pipeline", "deployment");
    private static final Set<String> PIPELINE_STATES = Set.of("running", "success", "failed", "canceled");
    private static final Set<String> DEPLOYMENT_STATES = Set.of("running", "success", "failed");
    /** The tools whose links are checked against the connection's tool address instead of the repository's. */
    private static final Set<String> NOTICE_TOOLS = Set.of("jenkins", "argocd");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{7,64}");
    /** A standard notice's time must lie in this range (a far-future time would win every later comparison). */
    private static final OffsetDateTime EARLIEST = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    private final DSLContext db;
    private final JsonMapper json;
    private final CicdLinker linker;
    private final TransactionTemplate tx;
    private final CicdCommitStore commits;
    private final CicdBuilds builds;
    private final CicdDeployments deployments;

    public CicdProcessor(DSLContext db, JsonMapper json, CicdLinker linker, TransactionTemplate tx, CicdCommitStore commits, CicdBuilds builds,
            CicdDeployments deployments) {
        this.db = db;
        this.json = json;
        this.linker = linker;
        this.tx = tx;
        this.commits = commits;
        this.builds = builds;
        this.deployments = deployments;
    }

    /** @return the number of events handled (done or failed) and deployments applied to their environments */
    public int run(Instant now) {
        int handled = 0;
        while (handled < MAX_PER_RUN) {
            var ids = db.select(CICD_EVENTS.ID).from(CICD_EVENTS).where(CICD_EVENTS.STATUS.eq("pending"), CICD_EVENTS.KIND.in(HANDLED_KINDS))
                    .orderBy(CICD_EVENTS.RECEIVED_AT, CICD_EVENTS.ID).limit(BATCH).fetch(CICD_EVENTS.ID);
            int progress = 0;
            for (var id : ids) {
                var outcome = one(id, now);
                if (outcome != Outcome.SKIPPED) progress++;
            }
            handled += progress;
            if (ids.size() < BATCH || progress == 0) break;
        }
        // then the environments: a few passes, so a deployment waiting behind one applied in this run goes in too
        for (int i = 0; i < 3; i++) {
            int n = deployments.advance(now);
            handled += n;
            if (n == 0) break;
        }
        return handled;
    }

    /** The processor's own failure with a fixed message that is safe to store. */
    private static final class BadEvent extends RuntimeException {
        BadEvent(String message) {
            super(message);
        }
    }

    private enum Outcome { DONE, FAILED, SKIPPED }

    private Outcome one(UUID id, Instant now) {
        try {
            var done = tx.execute(st -> {
                var row = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.ID.eq(id), CICD_EVENTS.STATUS.eq("pending"))
                        .forUpdate().skipLocked().fetchOne();
                if (row == null) return false;   // someone else has it or it is done
                var event = parse(row.getKind(), row.getPayload(), now);
                if (!process(row.getConnectionId(), row.getRepositoryId(), row.getKind(), event)) {
                    // not attached: forget the event, so the gap-filling poll can store the same fact once the repository is attached
                    db.deleteFrom(CICD_EVENTS).where(CICD_EVENTS.ID.eq(id)).execute();
                    return true;
                }
                db.update(CICD_EVENTS).set(CICD_EVENTS.STATUS, "done").set(CICD_EVENTS.PROCESSED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(CICD_EVENTS.ID.eq(id)).execute();
                return true;
            });
            return Boolean.TRUE.equals(done) ? Outcome.DONE : Outcome.SKIPPED;
        } catch (Exception e) {
            log.warn("cicd event {} failed: {}", id, e.getClass().getSimpleName());
            db.update(CICD_EVENTS).set(CICD_EVENTS.STATUS, "failed").set(CICD_EVENTS.ERROR, errorText(e))
                    .set(CICD_EVENTS.PROCESSED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .where(CICD_EVENTS.ID.eq(id), CICD_EVENTS.STATUS.eq("pending")).execute();
            return Outcome.FAILED;
        }
    }

    /** The class name and the first line of a short message; JSON and SQL messages can quote content, so those get a fixed text. */
    static String errorText(Exception e) {
        var name = e.getClass().getSimpleName();
        String msg;
        if (e instanceof JacksonException) msg = "unreadable payload";
        else if (e instanceof BadEvent) msg = firstLine(e.getMessage());
        else msg = "";
        var out = msg.isEmpty() ? name : name + ": " + msg;
        return out.length() > 500 ? out.substring(0, 500) : out;
    }

    private static String firstLine(String m) {
        if (m == null) return "";
        var line = m.lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private CicdEvent parse(String kind, JSONB payload, Instant now) {
        var e = json.readValue(payload.data(), CicdEvent.class);
        if (e == null) throw new BadEvent("empty event");
        switch (kind) {
            case "commit" -> {
                var c = e.commit();
                if (c == null || c.sha() == null || c.committedAt() == null) throw new BadEvent("incomplete commit event");
            }
            case "merge_request" -> {
                var m = e.mr();
                if (m == null || m.title() == null || m.updatedAt() == null || m.state() == null) throw new BadEvent("incomplete merge request event");
            }
            case "pipeline" -> {
                var p = e.pipeline();
                if (p == null || p.externalId() == null || p.sha() == null || p.status() == null || p.updatedAt() == null)
                    throw new BadEvent("incomplete pipeline event");
                if (!PIPELINE_STATES.contains(p.status()) || !SHA.matcher(p.sha()).matches()) throw new BadEvent("unusable pipeline event");
                if (e.repoPath() != null) inRange(p.updatedAt(), now);
            }
            case "deployment" -> {
                var d = e.deployment();
                if (d == null || d.externalId() == null || d.environment() == null || d.status() == null || d.deployedAt() == null)
                    throw new BadEvent("incomplete deployment event");
                if (!DEPLOYMENT_STATES.contains(d.status())) throw new BadEvent("unusable deployment event");
                if (e.repoPath() != null) inRange(d.deployedAt(), now);
            }
            default -> throw new BadEvent("unknown event kind");
        }
        return e;
    }

    /** A standard notice's own time: from 2000-01-01 to a day after now. */
    private static void inRange(OffsetDateTime at, Instant now) {
        var t = at.toInstant();
        if (t.isBefore(EARLIEST.toInstant()) || t.isAfter(now.plus(Duration.ofDays(1)))) throw new BadEvent("notice time out of range");
    }

    private boolean process(UUID connectionId, UUID repositoryId, String kind, CicdEvent event) {
        var connection = db.select(CICD_CONNECTIONS.KIND, CICD_CONNECTIONS.TOOL_URL, CICD_CONNECTIONS.IMAGE_TAG_PATTERN).from(CICD_CONNECTIONS)
                .where(CICD_CONNECTIONS.ID.eq(connectionId)).fetchOne();
        if (connection == null) return false;
        var repo = event.repoPath() != null ? byPath(connectionId, event.repoPath()) : byId(connectionId, repositoryId, event.repoExternalId());
        if (repo == null) return false;
        var source = connection.value1();
        var linkBase = NOTICE_TOOLS.contains(source) ? connection.value2() : repo.webUrl();
        switch (kind) {
            case "commit" -> commits.upsert(repo, event.commit());
            case "merge_request" -> mergeRequest(repo.id(), repo.projectId(), repo.webUrl(), event.mr());
            case "pipeline" -> builds.pipeline(repo, source, linkBase, event.pipeline());
            default -> builds.deployment(repo, source, linkBase, connection.value3(), event.deployment());
        }
        return true;
    }

    private CicdRepoRef byId(UUID connectionId, UUID repositoryId, String externalId) {
        var repo = repositoryId == null ? null : db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repositoryId), CICD_REPOSITORIES.DETACHED_AT.isNull()).fetchOne();
        if (repo == null && externalId != null)   // the webhook came before the repository was attached
            repo = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(connectionId),
                    CICD_REPOSITORIES.EXTERNAL_ID.eq(externalId), CICD_REPOSITORIES.DETACHED_AT.isNull()).fetchOne();
        return repo == null ? null : new CicdRepoRef(repo.getId(), repo.getProjectId(), repo.getWebUrl());
    }

    /**
     * A standard notice (Jenkins, Argo CD) names its repository by path: the attached GitLab or GitHub repository with that
     * path (ignoring case) in one of the notice connection's allowed projects. None: as if not attached. Two or more (the same
     * path on GitLab and GitHub): dropped, and the log says how many, never the path.
     */
    private CicdRepoRef byPath(UUID connectionId, String path) {
        var hosts = CICD_CONNECTIONS.as("h");
        var found = db.select(CICD_REPOSITORIES.ID, CICD_REPOSITORIES.PROJECT_ID, CICD_REPOSITORIES.WEB_URL).from(CICD_REPOSITORIES)
                .join(hosts).on(hosts.ID.eq(CICD_REPOSITORIES.CONNECTION_ID))
                .where(hosts.KIND.in("gitlab", "github"), CICD_REPOSITORIES.DETACHED_AT.isNull(),
                        DSL.lower(CICD_REPOSITORIES.FULL_PATH).eq(DSL.lower(DSL.val(path))),
                        CICD_REPOSITORIES.PROJECT_ID.in(DSL.select(CICD_CONNECTION_PROJECTS.PROJECT_ID).from(CICD_CONNECTION_PROJECTS)
                                .where(CICD_CONNECTION_PROJECTS.CONNECTION_ID.eq(connectionId))))
                .fetch(r -> new CicdRepoRef(r.value1(), r.value2(), r.value3()));
        if (found.size() == 1) return found.get(0);
        if (found.size() > 1) log.warn("cicd standard notice of connection {} matches {} repositories; ignored", connectionId, found.size());
        return null;
    }

    /** GitLab sends "[REDACTED]" where it hides an address: that is no address. */
    static String email(String e) {
        if (e == null) return "";
        var s = e.strip();
        return s.equalsIgnoreCase("[REDACTED]") || !s.contains("@") ? "" : s;
    }

    /**
     * The link shown on the development tab: only an http(s) address on the repository's own host (scheme, host and port the
     * same as its web_url, no user part); anything else from a payload becomes the repository's address.
     */
    static String safeUrl(String url, String repoWebUrl) {
        var fallback = repoWebUrl == null ? "" : repoWebUrl;
        var same = CicdLinks.sameOrigin(url, fallback);
        return same.isEmpty() ? fallback : same;
    }

    /** A merged MR's merge (or squash) commit: lower-case hex, or null when missing or malformed. */
    static String mergeCommit(String sha) {
        if (sha == null) return null;
        var s = sha.strip().toLowerCase(java.util.Locale.ROOT);
        return SHA.matcher(s).matches() ? s : null;
    }

    private static String mrState(String s) {
        return switch (s) {
            case "opened", "open" -> "open";
            case "draft" -> "draft";
            case "merged" -> "merged";
            case "closed", "locked" -> "closed";
            default -> throw new BadEvent("unknown merge request state");
        };
    }

    private void mergeRequest(UUID repoId, UUID projectId, String repoWebUrl, CicdEvent.MergeRequest m) {
        var branch = m.sourceBranch() == null ? "" : m.sourceBranch();
        var state = mrState(m.state());
        // only a strictly newer updated_at changes the row: a late, older event must not roll the state back
        var row = db.insertInto(CICD_MERGE_REQUESTS)
                .set(CICD_MERGE_REQUESTS.REPOSITORY_ID, repoId).set(CICD_MERGE_REQUESTS.IID, m.iid())
                .set(CICD_MERGE_REQUESTS.TITLE, m.title()).set(CICD_MERGE_REQUESTS.STATE, state)
                .set(CICD_MERGE_REQUESTS.SOURCE_BRANCH, branch)
                .set(CICD_MERGE_REQUESTS.TARGET_BRANCH, m.targetBranch() == null ? "" : m.targetBranch())
                .set(CICD_MERGE_REQUESTS.AUTHOR_NAME, m.authorName() == null ? "" : m.authorName())
                .set(CICD_MERGE_REQUESTS.USER_ID, commits.userByEmail(m.authorEmail()))
                .set(CICD_MERGE_REQUESTS.MERGED_AT, m.mergedAt()).set(CICD_MERGE_REQUESTS.UPDATED_AT, m.updatedAt())
                .set(CICD_MERGE_REQUESTS.WEB_URL, safeUrl(m.webUrl(), repoWebUrl))
                .set(CICD_MERGE_REQUESTS.MERGE_COMMIT_SHA, state.equals("merged") ? mergeCommit(m.mergeCommitSha()) : null)
                .onConflict(CICD_MERGE_REQUESTS.REPOSITORY_ID, CICD_MERGE_REQUESTS.IID).doUpdate()
                .set(CICD_MERGE_REQUESTS.TITLE, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.TITLE))
                .set(CICD_MERGE_REQUESTS.STATE, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.STATE))
                .set(CICD_MERGE_REQUESTS.SOURCE_BRANCH, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.SOURCE_BRANCH))
                .set(CICD_MERGE_REQUESTS.TARGET_BRANCH, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.TARGET_BRANCH))
                // the author is the first one seen: a later event may only name who changed the MR, or no one
                .set(CICD_MERGE_REQUESTS.AUTHOR_NAME, org.jooq.impl.DSL.when(CICD_MERGE_REQUESTS.AUTHOR_NAME.eq(""),
                        org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.AUTHOR_NAME)).otherwise(CICD_MERGE_REQUESTS.AUTHOR_NAME))
                .set(CICD_MERGE_REQUESTS.USER_ID, org.jooq.impl.DSL.coalesce(CICD_MERGE_REQUESTS.USER_ID,
                        org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.USER_ID)))
                .set(CICD_MERGE_REQUESTS.MERGED_AT, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.MERGED_AT))
                .set(CICD_MERGE_REQUESTS.UPDATED_AT, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.UPDATED_AT))
                .set(CICD_MERGE_REQUESTS.WEB_URL, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.WEB_URL))
                .set(CICD_MERGE_REQUESTS.MERGE_COMMIT_SHA, org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.MERGE_COMMIT_SHA),
                        CICD_MERGE_REQUESTS.MERGE_COMMIT_SHA))
                .where(CICD_MERGE_REQUESTS.UPDATED_AT.lt(org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.UPDATED_AT)))
                .returning(CICD_MERGE_REQUESTS.ID).fetchOne();
        if (row == null) return;   // not newer: nothing changed, nothing to link
        var refs = RefParser.merge(RefParser.inBranch(branch), RefParser.inText(m.title(), "title"), RefParser.inText(m.description(), "title"));
        linker.link(repoId, projectId, refs, null, row.getId());
    }
}

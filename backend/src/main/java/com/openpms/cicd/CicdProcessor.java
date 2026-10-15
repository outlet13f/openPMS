package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_MERGE_REQUESTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static com.openpms.db.Tables.USERS;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job "cicd-process": turns the pending rows of cicd_events into commits, merge requests and links to work packages.
 * Each event runs in its own transaction, so one broken event only marks itself failed.
 */
@Component
public class CicdProcessor {
    private static final Logger log = LoggerFactory.getLogger(CicdProcessor.class);
    static final int BATCH = 200;
    /** Upper bound of events per run, so one run cannot go on forever under a steady flow. */
    private static final int MAX_PER_RUN = 5000;

    private final DSLContext db;
    private final JsonMapper json;
    private final CicdLinker linker;
    private final TransactionTemplate tx;

    public CicdProcessor(DSLContext db, JsonMapper json, CicdLinker linker, TransactionTemplate tx) {
        this.db = db;
        this.json = json;
        this.linker = linker;
        this.tx = tx;
    }

    /** @return the number of events handled (done or failed) */
    public int run(Instant now) {
        int handled = 0;
        while (handled < MAX_PER_RUN) {
            var ids = db.select(CICD_EVENTS.ID).from(CICD_EVENTS).where(CICD_EVENTS.STATUS.eq("pending"))
                    .orderBy(CICD_EVENTS.RECEIVED_AT, CICD_EVENTS.ID).limit(BATCH).fetch(CICD_EVENTS.ID);
            int progress = 0;
            for (var id : ids) {
                var outcome = one(id);
                if (outcome != Outcome.SKIPPED) progress++;
            }
            handled += progress;
            if (ids.size() < BATCH || progress == 0) break;
        }
        return handled;
    }

    private enum Outcome { DONE, FAILED, SKIPPED }

    private Outcome one(UUID id) {
        try {
            var done = tx.execute(st -> {
                var row = db.selectFrom(CICD_EVENTS).where(CICD_EVENTS.ID.eq(id), CICD_EVENTS.STATUS.eq("pending"))
                        .forUpdate().skipLocked().fetchOne();
                if (row == null) return false;   // someone else has it or it is done
                var event = parse(row.getKind(), row.getPayload());
                process(row.getConnectionId(), row.getRepositoryId(), row.getKind(), event);
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
        else if (e instanceof IllegalArgumentException) msg = firstLine(e.getMessage());
        else msg = "";
        var out = msg.isEmpty() ? name : name + ": " + msg;
        return out.length() > 500 ? out.substring(0, 500) : out;
    }

    private static String firstLine(String m) {
        if (m == null) return "";
        var line = m.lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private CicdEvent parse(String kind, JSONB payload) {
        var e = json.readValue(payload.data(), CicdEvent.class);
        if (e == null) throw new IllegalArgumentException("empty event");
        if (kind.equals("commit")) {
            var c = e.commit();
            if (c == null || c.sha() == null || c.committedAt() == null) throw new IllegalArgumentException("incomplete commit event");
        } else {
            var m = e.mr();
            if (m == null || m.title() == null || m.updatedAt() == null || m.state() == null) throw new IllegalArgumentException("incomplete merge request event");
        }
        return e;
    }

    private void process(UUID connectionId, UUID repositoryId, String kind, CicdEvent event) {
        var repo = repositoryId == null ? null : db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.ID.eq(repositoryId)).fetchOne();
        if (repo == null && event.repoExternalId() != null)   // the webhook came before the repository was attached
            repo = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(connectionId),
                    CICD_REPOSITORIES.EXTERNAL_ID.eq(event.repoExternalId())).fetchOne();
        if (repo == null) return;   // not attached: ignored
        if (kind.equals("commit")) commit(repo.getId(), repo.getProjectId(), event.commit());
        else mergeRequest(repo.getId(), repo.getProjectId(), event.mr());
    }

    private UUID userByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        var r = db.select(USERS.ID).from(USERS).where(org.jooq.impl.DSL.lower(USERS.EMAIL).eq(email.toLowerCase()), USERS.STATUS.notIn("locked", "deleted")).fetchOne();
        return r == null ? null : r.value1();
    }

    private void commit(UUID repoId, UUID projectId, CicdEvent.Commit c) {
        var id = db.insertInto(CICD_COMMITS)
                .set(CICD_COMMITS.REPOSITORY_ID, repoId).set(CICD_COMMITS.SHA, c.sha())
                .set(CICD_COMMITS.TITLE, c.title() == null ? "" : c.title())
                .set(CICD_COMMITS.AUTHOR_NAME, c.authorName() == null ? "" : c.authorName())
                .set(CICD_COMMITS.AUTHOR_EMAIL, c.authorEmail() == null ? "" : c.authorEmail())
                .set(CICD_COMMITS.USER_ID, userByEmail(c.authorEmail()))
                .set(CICD_COMMITS.COMMITTED_AT, c.committedAt())
                .set(CICD_COMMITS.WEB_URL, c.webUrl() == null ? "" : c.webUrl())
                .onConflict(CICD_COMMITS.REPOSITORY_ID, CICD_COMMITS.SHA).doNothing()
                .returning(CICD_COMMITS.ID).fetchOne();
        if (id == null) return;   // already known: its links were made then
        linker.link(repoId, projectId, RefParser.inText(c.message(), "message"), id.getId(), null);
    }

    private static String mrState(String s) {
        return switch (s) {
            case "opened", "open" -> "open";
            case "draft" -> "draft";
            case "merged" -> "merged";
            case "closed", "locked" -> "closed";
            default -> throw new IllegalArgumentException("unknown merge request state");
        };
    }

    private void mergeRequest(UUID repoId, UUID projectId, CicdEvent.MergeRequest m) {
        var branch = m.sourceBranch() == null ? "" : m.sourceBranch();
        // only a strictly newer updated_at changes the row: a late, older event must not roll the state back
        var row = db.insertInto(CICD_MERGE_REQUESTS)
                .set(CICD_MERGE_REQUESTS.REPOSITORY_ID, repoId).set(CICD_MERGE_REQUESTS.IID, m.iid())
                .set(CICD_MERGE_REQUESTS.TITLE, m.title()).set(CICD_MERGE_REQUESTS.STATE, mrState(m.state()))
                .set(CICD_MERGE_REQUESTS.SOURCE_BRANCH, branch)
                .set(CICD_MERGE_REQUESTS.TARGET_BRANCH, m.targetBranch() == null ? "" : m.targetBranch())
                .set(CICD_MERGE_REQUESTS.AUTHOR_NAME, m.authorName() == null ? "" : m.authorName())
                .set(CICD_MERGE_REQUESTS.USER_ID, userByEmail(m.authorEmail()))
                .set(CICD_MERGE_REQUESTS.MERGED_AT, m.mergedAt()).set(CICD_MERGE_REQUESTS.UPDATED_AT, m.updatedAt())
                .set(CICD_MERGE_REQUESTS.WEB_URL, m.webUrl() == null ? "" : m.webUrl())
                .onConflict(CICD_MERGE_REQUESTS.REPOSITORY_ID, CICD_MERGE_REQUESTS.IID).doUpdate()
                .set(CICD_MERGE_REQUESTS.TITLE, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.TITLE))
                .set(CICD_MERGE_REQUESTS.STATE, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.STATE))
                .set(CICD_MERGE_REQUESTS.SOURCE_BRANCH, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.SOURCE_BRANCH))
                .set(CICD_MERGE_REQUESTS.TARGET_BRANCH, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.TARGET_BRANCH))
                .set(CICD_MERGE_REQUESTS.AUTHOR_NAME, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.AUTHOR_NAME))
                .set(CICD_MERGE_REQUESTS.USER_ID, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.USER_ID))
                .set(CICD_MERGE_REQUESTS.MERGED_AT, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.MERGED_AT))
                .set(CICD_MERGE_REQUESTS.UPDATED_AT, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.UPDATED_AT))
                .set(CICD_MERGE_REQUESTS.WEB_URL, org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.WEB_URL))
                .where(CICD_MERGE_REQUESTS.UPDATED_AT.lt(org.jooq.impl.DSL.excluded(CICD_MERGE_REQUESTS.UPDATED_AT)))
                .returning(CICD_MERGE_REQUESTS.ID).fetchOne();
        if (row == null) return;   // not newer: nothing changed, nothing to link
        var refs = RefParser.merge(RefParser.inBranch(branch), RefParser.inText(m.title(), "title"), RefParser.inText(m.description(), "title"));
        linker.link(repoId, projectId, refs, null, row.getId());
    }
}

package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Jobs "cicd-poll" and "cicd-cleanup". Polling fills the gaps a webhook may have left: for each repository of an active
 * connection with an API token it asks the host what changed since the cursor (less an overlap, the unique delivery key
 * drops what is already stored). The host is called outside any transaction. A failing repository is logged by ids and
 * status only (never the URL, query or token) and the run goes on; a host that does not answer at all (refused, timed out)
 * is not asked again for its other repositories in the same run, so one dead server cannot hold the job for minutes.
 */
@Component
public class CicdPoller {
    private static final Logger log = LoggerFactory.getLogger(CicdPoller.class);
    static final Duration OVERLAP = Duration.ofMinutes(10);
    static final Duration FIRST_LOOK_BACK = Duration.ofDays(7);
    static final Duration KEEP = Duration.ofDays(90);

    private final DSLContext db;
    private final Adapters adapters;
    private final SecretBox box;
    private final CicdEvents events;

    public CicdPoller(DSLContext db, Adapters adapters, SecretBox box, CicdEvents events) {
        this.db = db;
        this.adapters = adapters;
        this.box = box;
        this.events = events;
    }

    /** @return the number of new events stored */
    public int poll(Instant now) {
        var rows = db.select(CICD_REPOSITORIES.ID, CICD_REPOSITORIES.CONNECTION_ID, CICD_REPOSITORIES.EXTERNAL_ID,
                        CICD_REPOSITORIES.POLL_CURSOR, CICD_CONNECTIONS.KIND, CICD_CONNECTIONS.BASE_URL, CICD_CONNECTIONS.API_TOKEN_ENC)
                .from(CICD_REPOSITORIES).join(CICD_CONNECTIONS).on(CICD_CONNECTIONS.ID.eq(CICD_REPOSITORIES.CONNECTION_ID))
                .where(CICD_REPOSITORIES.DETACHED_AT.isNull().and(CICD_CONNECTIONS.ACTIVE.isTrue()).and(CICD_CONNECTIONS.API_TOKEN_ENC.isNotNull()).and(CICD_CONNECTIONS.API_TOKEN_ENC.ne("")))
                .orderBy(CICD_REPOSITORIES.CREATED_AT, CICD_REPOSITORIES.ID).fetch();
        int stored = 0;
        var unreachable = new java.util.HashSet<java.util.UUID>();
        for (var r : rows) {
            var repoId = r.get(CICD_REPOSITORIES.ID);
            var connectionId = r.get(CICD_REPOSITORIES.CONNECTION_ID);
            if (unreachable.contains(connectionId)) continue;   // the host did not answer: its other repositories wait for the next run
            try {
                var cursor = r.get(CICD_REPOSITORIES.POLL_CURSOR);
                var since = cursor != null ? cursor.minus(OVERLAP) : now.minus(FIRST_LOOK_BACK).atOffset(ZoneOffset.UTC);
                var remote = new CicdAdapter.Remote(r.get(CICD_CONNECTIONS.BASE_URL), box.open(r.get(CICD_CONNECTIONS.API_TOKEN_ENC)));
                var polled = adapters.of(r.get(CICD_CONNECTIONS.KIND)).poll(remote, r.get(CICD_REPOSITORIES.EXTERNAL_ID), since);
                var found = polled.events();
                if (polled.truncated()) log.warn("cicd-poll: poll window truncated: connection {} repository {}", connectionId, repoId);
                stored += events.store(connectionId, found, "poll");
                OffsetDateTime newest = cursor != null ? cursor : since;
                for (var e : found) {
                    var t = e.commit() != null ? e.commit().committedAt() : e.mr() != null ? e.mr().updatedAt()
                            : e.pipeline() != null ? e.pipeline().updatedAt() : e.deployment() != null ? e.deployment().deployedAt() : null;
                    if (t != null && t.toInstant().isAfter(newest.toInstant())) newest = t;
                }
                // a timestamp in the future (clock skew, bad data) must not push the cursor ahead of this poll
                var ceiling = now.atOffset(ZoneOffset.UTC);
                if (newest.toInstant().isAfter(ceiling.toInstant())) newest = ceiling;
                db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.POLL_CURSOR, newest)
                        .set(CICD_REPOSITORIES.LAST_POLLED_AT, now.atOffset(ZoneOffset.UTC))
                        .where(CICD_REPOSITORIES.ID.eq(repoId)).execute();
            } catch (IOException | RuntimeException e) {
                if (unreachable(e)) {
                    unreachable.add(connectionId);
                    log.warn("cicd-poll: connection {} unreachable ({}), its other repositories are skipped in this run", connectionId, describe(e));
                } else {
                    log.warn("cicd-poll: connection {} repository {} failed: {}", connectionId, repoId, describe(e));
                }
            }
        }
        return stored;
    }

    /** @return the number of old events deleted (pending ones are never deleted) */
    public int cleanup(Instant now) {
        return db.deleteFrom(CICD_EVENTS)
                .where(CICD_EVENTS.RECEIVED_AT.lt(now.minus(KEEP).atOffset(ZoneOffset.UTC)).and(CICD_EVENTS.STATUS.ne("pending")))
                .execute();
    }

    /** No answer at all (refused, reset, timed out), as opposed to an answer with a bad status or body. */
    static boolean unreachable(Exception e) {
        if (!(e instanceof IOException) || e.getMessage() == null) return false;
        return e.getMessage().equals("request timed out") || e.getMessage().startsWith("request failed");
    }

    /** The adapters' IOExceptions carry fixed messages ("unexpected status 500", "request timed out"): no URL, query or token. */
    private static String describe(Exception e) {
        if (e instanceof IOException && e.getMessage() != null) return e.getMessage();
        return e.getClass().getSimpleName();
    }
}

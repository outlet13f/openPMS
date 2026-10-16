package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** The inbox of events from the code hosts (webhook and polling). Storing never processes: the job reads the pending rows. */
@Service
public class CicdEvents {
    private final DSLContext db;
    private final JsonMapper json;

    public CicdEvents(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Stores the events of one connection; a delivery key already stored is skipped.
     * via: "webhook" or "poll". Returns how many rows are new.
     */
    public int store(UUID connectionId, List<CicdEvent> events, String via) {
        if (events.isEmpty()) return 0;
        Map<String, UUID> repos = new HashMap<>();
        for (var r : db.select(CICD_REPOSITORIES.EXTERNAL_ID, CICD_REPOSITORIES.ID).from(CICD_REPOSITORIES)
                .where(CICD_REPOSITORIES.CONNECTION_ID.eq(connectionId), CICD_REPOSITORIES.DETACHED_AT.isNull()).fetch())
            repos.put(r.value1(), r.value2());
        int added = 0;
        for (var e : events) {
            added += db.insertInto(CICD_EVENTS)
                    .set(CICD_EVENTS.CONNECTION_ID, connectionId)
                    .set(CICD_EVENTS.REPOSITORY_ID, repos.get(e.repoExternalId()))
                    .set(CICD_EVENTS.KIND, e.kind())
                    .set(CICD_EVENTS.DELIVERY_KEY, e.deliveryKey())
                    .set(CICD_EVENTS.PAYLOAD, JSONB.valueOf(json.writeValueAsString(e)))
                    .set(CICD_EVENTS.VIA, via)
                    .onConflict(CICD_EVENTS.CONNECTION_ID, CICD_EVENTS.DELIVERY_KEY).doNothing()
                    .execute();
        }
        return added;
    }
}

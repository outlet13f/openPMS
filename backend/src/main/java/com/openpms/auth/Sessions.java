package com.openpms.auth;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Ends a person's sessions on every device (Spring Session JDBC keys sessions by principal name = user id). */
@Component
public class Sessions {
    private final DSLContext db;

    public Sessions(DSLContext db) {
        this.db = db;
    }

    /** Every session of this person except the one with this id (the browser asking). */
    public void endOthers(UUID userId, String keepSessionId) {
        db.deleteFrom(table("spring_session")).where(field("principal_name", String.class).eq(userId.toString()), field("session_id", String.class).ne(keepSessionId))
                .execute();
    }

    public void endAll(UUID userId) {
        db.deleteFrom(table("spring_session")).where(field("principal_name", String.class).eq(userId.toString())).execute();
    }
}

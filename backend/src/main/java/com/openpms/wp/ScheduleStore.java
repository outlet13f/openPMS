package com.openpms.wp;

import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.core.Ctx;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Loads the scheduling graph and writes the scheduler's changes back, one journal per moved item (S7). */
@Component
public class ScheduleStore {
    /** pg_advisory_xact_lock key that serialises schedule-changing transactions (S9). */
    private static final long LOCK_KEY = 0x6f70656e706d73L;

    private final DSLContext db;
    private final Journals journals;

    public ScheduleStore(DSLContext db, Journals journals) {
        this.db = db;
        this.journals = journals;
    }

    /** Must run inside the caller's transaction; released at commit or rollback. */
    public void lock() {
        db.execute("select pg_advisory_xact_lock(?)", LOCK_KEY);
    }

    public Schedule load(Ctx ctx) {
        var s = ctx.s();
        var nodes = db.select(WORK_PACKAGES.ID, WORK_PACKAGES.NUMBER, WORK_PACKAGES.SUBJECT, WORK_PACKAGES.PARENT_ID, WORK_PACKAGES.TYPE_ID,
                        WORK_PACKAGES.START_DATE, WORK_PACKAGES.DUE_DATE, WORK_PACKAGES.DURATION, WORK_PACKAGES.IGNORE_NON_WORKING_DAYS,
                        WORK_PACKAGES.SCHEDULE_MANUALLY)
                .from(WORK_PACKAGES).fetch()
                .map(r -> new Schedule.Node(r.value1(), r.value2(), r.value3(), r.value4(),
                        s.type(r.value5()).map(t -> t.isMilestone()).orElse(false), r.value6(), r.value7(), r.value8(), r.value9(), r.value10()));
        var links = db.select(RELATIONS.FROM_ID, RELATIONS.TO_ID, RELATIONS.LAG).from(RELATIONS).where(RELATIONS.TYPE.eq("precedes")).fetch()
                .map(r -> new Schedule.Link(r.value1(), r.value2(), r.value3()));
        return new Schedule(nodes, links, s.calendar());
    }

    /** Persist moved items: new dates, a bumped lock version and a journal with the cause. */
    public void apply(Ctx ctx, List<Schedule.Change> changes) {
        var now = OffsetDateTime.now();
        for (var c : changes) {
            var n = c.node();
            db.update(WORK_PACKAGES)
                    .set(WORK_PACKAGES.START_DATE, n.start).set(WORK_PACKAGES.DUE_DATE, n.due).set(WORK_PACKAGES.DURATION, n.duration)
                    .set(WORK_PACKAGES.LOCK_VERSION, WORK_PACKAGES.LOCK_VERSION.plus(1)).set(WORK_PACKAGES.UPDATED_AT, now)
                    .where(WORK_PACKAGES.ID.eq(n.id)).execute();
            var rec = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(n.id)).fetchOne();
            journals.write(ctx, rec, c.changes(), "", c.cause(), false);
        }
    }
}

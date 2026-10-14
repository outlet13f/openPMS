package com.openpms.itsm;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.common.Mailer;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.wp.Journals;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Mail to requesters, who are not project members and so get nothing from the notification centre (ITSM 1단계): a receipt,
 * then one mail per batch of public answers and status changes by other people. Internal comments, the requester's own
 * entries and changes to anything but the status never go into a mail.
 *
 * <p>Delivery is at most once: requester_mailed_at is moved (only if still the value we read, so two instances do not both
 * send) <em>before</em> the mail is handed over. If the relay refuses it, that one mail is lost rather than sent twice
 * (MailJobs is the other way round, at least once).
 *
 * <p>Journals are judged by updated_at, not created_at: an agent's consecutive plain changes are merged into their previous
 * entry (Journals.write), so a status change can arrive in an entry that was created before the last mail.
 */
@Component
public class PortalMail {
    static final Duration SETTLE = Duration.ofMinutes(1);

    private final DSLContext db;
    private final Mailer mailer;
    private final Current current;
    private final Journals journals;

    public PortalMail(DSLContext db, Mailer mailer, Current current, Journals journals) {
        this.db = db;
        this.mailer = mailer;
        this.current = current;
        this.journals = journals;
    }

    /** @return the number of mails sent */
    public int run(Instant now) {
        var s = current.snapshot();
        int sent = 0;
        var settled = now.minus(SETTLE).atOffset(ZoneOffset.UTC);
        var rows = db.select(SERVICE_TICKETS.WORK_PACKAGE_ID, SERVICE_TICKETS.REQUESTER_ID, SERVICE_TICKETS.REQUESTER_MAILED_AT, WORK_PACKAGES.NUMBER,
                        WORK_PACKAGES.SUBJECT, WORK_PACKAGES.PROJECT_ID, USERS.EMAIL, USERS.STATUS)
                .from(SERVICE_TICKETS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(SERVICE_TICKETS.WORK_PACKAGE_ID))
                .join(USERS).on(USERS.ID.eq(SERVICE_TICKETS.REQUESTER_ID))
                .where(SERVICE_TICKETS.REQUESTER_MAILED_AT.isNull().or(DSL.exists(DSL.selectOne().from(JOURNALS).where(
                        JOURNALS.WORK_PACKAGE_ID.eq(SERVICE_TICKETS.WORK_PACKAGE_ID), JOURNALS.IS_INTERNAL.isFalse(),
                        JOURNALS.USER_ID.ne(SERVICE_TICKETS.REQUESTER_ID), JOURNALS.UPDATED_AT.gt(SERVICE_TICKETS.REQUESTER_MAILED_AT),
                        JOURNALS.UPDATED_AT.le(settled)))))
                .fetch();
        for (var r : rows) {
            UUID wpId = r.value1();
            OffsetDateTime last = r.value3();
            String subject, body;
            var link = mailer.link("/portal/tickets/" + r.value4());
            if (last == null) {
                var desk = s.project(r.value6()).map(Snapshot.ProjectRow::name).orElse("서비스 데스크");
                subject = "[openPMS] #" + r.value4() + " 요청이 접수되었습니다: " + r.value5();
                body = desk + "에 요청이 접수되었습니다.\n\n" + r.value5() + "\n\n진행 상황: " + link + "\n";
            } else {
                var text = new StringBuilder();
                boolean resolved = false;
                for (var j : db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wpId), JOURNALS.IS_INTERNAL.isFalse(),
                        JOURNALS.USER_ID.ne(r.value2()), JOURNALS.UPDATED_AT.gt(last), JOURNALS.UPDATED_AT.le(settled))
                        .orderBy(JOURNALS.VERSION).fetch()) {
                    var who = Refs.user(s, j.getUserId()).name();
                    var changes = journals.changesOf(j);
                    var st = changes.containsKey(Journals.CREATED) ? null : changes.get("statusId");
                    if (st != null && st.size() == 2 && st.get(1) != null) {
                        var to = s.status(UUID.fromString(String.valueOf(st.get(1)))).orElse(null);
                        var from = st.get(0) == null ? null : s.status(UUID.fromString(String.valueOf(st.get(0)))).orElse(null);
                        if (to != null) {
                            text.append(who).append(" - 상태: ").append(from == null ? "" : from.name() + " → ").append(to.name()).append('\n');
                            resolved = ItsmIds.STATUS_RESOLVED.equals(to.id()) || ItsmIds.STATUS_CLOSED.equals(to.id());
                        }
                    }
                    if (!j.getNotesText().isEmpty()) text.append(who).append(":\n").append(j.getNotesText()).append("\n\n");
                }
                if (text.isEmpty()) {
                    mark(wpId, last, now);
                    continue;
                }
                subject = "[openPMS] #" + r.value4() + (resolved ? " 요청이 해결되었습니다: " : " 새 소식: ") + r.value5();
                body = text + "\n진행 상황: " + link + "\n";
            }
            if (!mark(wpId, last, now)) continue; // another instance took it
            if ("active".equals(r.value8()) && mailer.send(r.value7(), subject, body)) sent++;
        }
        return sent;
    }

    /** Moves requester_mailed_at on, but only if nobody else did since we read it. A minute behind now, so entries still settling go into the next batch. */
    private boolean mark(UUID wpId, OffsetDateTime last, Instant now) {
        var cond = last == null ? SERVICE_TICKETS.REQUESTER_MAILED_AT.isNull() : SERVICE_TICKETS.REQUESTER_MAILED_AT.eq(last);
        return db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, now.minus(SETTLE).atOffset(ZoneOffset.UTC))
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId), cond).execute() == 1;
    }
}

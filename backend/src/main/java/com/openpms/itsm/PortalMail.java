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
 * entry (Journals.write), so a status change can arrive in an entry that was created before the last mail. For the same reason
 * a status is mailed only when it differs from requester_mailed_status_id, so a mailed entry that later grows does not repeat it.
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
                        WORK_PACKAGES.SUBJECT, WORK_PACKAGES.PROJECT_ID, USERS.EMAIL, USERS.STATUS, WORK_PACKAGES.STATUS_ID,
                        SERVICE_TICKETS.REQUESTER_MAILED_STATUS_ID)
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
            UUID currentStatus = r.value9();
            UUID mailedStatus = r.value10();
            // an archived desk or one whose service desk is off: the portal link would say "not found" (PortalService.find).
            // Nothing is marked, so the news goes out if the desk comes back.
            var deskRow = s.project(r.value6()).filter(x -> x.enabledModules().contains(ItsmIds.MODULE) && x.archivedAt() == null).orElse(null);
            if (deskRow == null) continue;
            String subject, body;
            UUID told = null; // the status this mail announces; stays null (marker unchanged) when it has no status line
            if (last == null) {
                var link = portalLink(r.value4());
                var desk = deskRow.name();
                subject = "[openPMS] #" + r.value4() + " 요청이 접수되었습니다: " + r.value5();
                body = desk + "에 요청이 접수되었습니다.\n\n" + r.value5() + "\n\n진행 상황: " + link + "\n";
            } else {
                var text = new StringBuilder();
                String statusAuthor = null;
                for (var j : db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wpId), JOURNALS.IS_INTERNAL.isFalse(),
                        JOURNALS.USER_ID.ne(r.value2()), JOURNALS.UPDATED_AT.gt(last), JOURNALS.UPDATED_AT.le(settled))
                        .orderBy(JOURNALS.VERSION).fetch()) {
                    var who = Refs.user(s, j.getUserId()).name();
                    var changes = journals.changesOf(j);
                    if (!changes.containsKey(Journals.CREATED) && changes.containsKey("statusId")) statusAuthor = who;
                    if (!j.getNotesText().isEmpty()) text.append(who).append(":\n").append(j.getNotesText()).append("\n\n");
                }
                // the status is judged by where it stands now against what the requester was last told: a journal that was
                // mailed and then grew by a merged plain edit does not say it again
                boolean resolved = false;
                var currentRow = currentStatus == null ? null : s.status(currentStatus).orElse(null);
                if (statusAuthor != null && currentRow != null && !currentRow.id().equals(mailedStatus)) {
                    var was = mailedStatus == null ? null : s.status(mailedStatus).map(Snapshot.StatusRow::name).orElse(null);
                    text.insert(0, statusAuthor + " - 상태: " + (was == null ? "접수" : was) + " → " + currentRow.name() + "\n\n");
                    told = currentRow.id();
                    resolved = ItsmIds.STATUS_RESOLVED.equals(currentRow.id()) || ItsmIds.STATUS_CLOSED.equals(currentRow.id());
                }
                if (text.isEmpty()) {
                    mark(wpId, last, now, null);
                    continue;
                }
                var link = portalLink(r.value4());
                subject = "[openPMS] #" + r.value4() + (resolved ? " 요청이 해결되었습니다: " : " 새 소식: ") + r.value5();
                body = text + "진행 상황: " + link + "\n";
            }
            if (!mark(wpId, last, now, last == null ? currentStatus : told)) continue; // another instance took it
            if ("active".equals(r.value8()) && mailer.send(r.value7(), subject, body)) sent++;
        }
        return sent;
    }

    private String portalLink(long number) {
        return mailer.link("/portal/tickets/" + number);
    }

    /** Moves requester_mailed_at (and, when a status line went out or on the receipt, the status the requester now knows) on, but only if nobody else did since we read it. A minute behind now, so entries still settling go into the next batch. */
    private boolean mark(UUID wpId, OffsetDateTime last, Instant now, UUID status) {
        var cond = last == null ? SERVICE_TICKETS.REQUESTER_MAILED_AT.isNull() : SERVICE_TICKETS.REQUESTER_MAILED_AT.eq(last);
        return db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_MAILED_AT, now.minus(SETTLE).atOffset(ZoneOffset.UTC))
                .set(SERVICE_TICKETS.REQUESTER_MAILED_STATUS_ID, DSL.coalesce(DSL.val(status, SERVICE_TICKETS.REQUESTER_MAILED_STATUS_ID), SERVICE_TICKETS.REQUESTER_MAILED_STATUS_ID))
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(wpId), cond).execute() == 1;
    }
}

package com.openpms.itsm;

import static com.openpms.db.Tables.CATALOG_ITEMS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.TICKET_APPROVALS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.common.Dates;
import com.openpms.common.Mailer;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mail to approvers (ITSM 2단계). An approver is not necessarily a project member and so gets nothing from the notification
 * centre: this job mails them when a request reaches their turn (a pending row never mailed), and once more when three
 * working days have passed without a decision. The global work calendar (Snapshot.calendar(), 관리 › 달력과 날짜) says which days work; the count runs from the day
 * after the first mail up to today (Asia/Seoul).
 *
 * <p>Delivery is at most once, like {@link PortalMail}: notified_at / reminded_at are set (only if still unset, so two instances
 * do not both send) <em>before</em> the mail is handed over. If the relay refuses it, that mail is lost rather than sent twice.
 * An approver who is no longer active is marked but not mailed. A request on an archived desk or one whose service desk is
 * off is skipped without marking, so the mail goes out if the desk comes back.
 */
@Component
public class ApprovalMail {
    private static final Logger log = LoggerFactory.getLogger(ApprovalMail.class);
    static final int REMIND_AFTER_WORKING_DAYS = 3;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DSLContext db;
    private final Mailer mailer;
    private final Current current;

    public ApprovalMail(DSLContext db, Mailer mailer, Current current) {
        this.db = db;
        this.mailer = mailer;
        this.current = current;
    }

    /** @return the number of mails sent */
    public int run(Instant now) {
        var s = current.snapshot();
        var cal = s.calendar();
        var today = now.atZone(KST).toLocalDate();
        var rows = db.select(TICKET_APPROVALS.ID, TICKET_APPROVALS.NOTIFIED_AT, TICKET_APPROVALS.REMINDED_AT, WORK_PACKAGES.NUMBER, WORK_PACKAGES.SUBJECT,
                        WORK_PACKAGES.PROJECT_ID, USERS.EMAIL, USERS.STATUS, CATALOG_ITEMS.NAME, SERVICE_TICKETS.REQUESTER_ID,
                        TICKET_APPROVALS.APPROVER_ID)
                .from(TICKET_APPROVALS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(TICKET_APPROVALS.WORK_PACKAGE_ID))
                .join(SERVICE_TICKETS).on(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(TICKET_APPROVALS.WORK_PACKAGE_ID))
                .join(USERS).on(USERS.ID.eq(TICKET_APPROVALS.APPROVER_ID))
                .leftJoin(CATALOG_ITEMS).on(CATALOG_ITEMS.ID.eq(SERVICE_TICKETS.CATALOG_ITEM_ID))
                .where(TICKET_APPROVALS.DECISION.eq("pending"), TICKET_APPROVALS.REMINDED_AT.isNull())
                .fetch();
        int sent = 0;
        for (var r : rows) {
            var id = r.value1();
            OffsetDateTime notified = r.value2();
            try {
                var desk = s.project(r.value6()).filter(x -> x.enabledModules().contains(ItsmIds.MODULE) && x.archivedAt() == null).orElse(null);
                if (desk == null) continue;
                boolean reminder = notified != null;
                if (reminder && workingDaysSince(notified.atZoneSameInstant(KST).toLocalDate(), today, cal) < REMIND_AFTER_WORKING_DAYS) continue;
                var cond = reminder ? TICKET_APPROVALS.REMINDED_AT.isNull().and(TICKET_APPROVALS.NOTIFIED_AT.eq(notified)) : TICKET_APPROVALS.NOTIFIED_AT.isNull();
                var field = reminder ? TICKET_APPROVALS.REMINDED_AT : TICKET_APPROVALS.NOTIFIED_AT;
                // marked first: a mail that fails is lost, one that succeeds is never repeated
                if (db.update(TICKET_APPROVALS).set(field, now.atOffset(ZoneOffset.UTC))
                        .where(TICKET_APPROVALS.ID.eq(id), TICKET_APPROVALS.DECISION.eq("pending"),
                                TICKET_APPROVALS.APPROVER_ID.eq(r.value11()), cond).execute() != 1) continue;
                if (!"active".equals(r.value8())) continue;
                var subject = "[openPMS] " + (reminder ? "승인 대기 중: #" : "승인 요청: #") + r.value4() + " " + r.value5();
                var requester = Refs.user(s, r.value10()).name();
                var item = r.value9() == null ? "(항목 없음)" : r.value9();
                var link = mailer.link("/portal/approvals/" + id);
                var body = (reminder ? requester + "님의 요청이 아직 승인을 기다리고 있습니다.\n" : requester + "님이 승인을 요청했습니다.\n")
                        + "\n항목: " + item + "\n제목: " + r.value5() + "\n\n승인 또는 반려하기: " + link + "\n";
                if (mailer.send(r.value7(), subject, body)) sent++;
            } catch (RuntimeException e) {
                log.error("approval mail failed for approval {}", id, e);
            }
        }
        return sent;
    }

    /** Working days after from (exclusive) up to and including to. */
    static int workingDaysSince(LocalDate from, LocalDate to, Dates.WorkCalendar cal) {
        int n = 0;
        for (var d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) if (cal.isWorking(d, false)) n++;
        return n;
    }
}

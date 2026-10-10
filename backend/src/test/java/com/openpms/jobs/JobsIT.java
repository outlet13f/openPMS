package com.openpms.jobs;

import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.NOTIFICATION_SETTINGS;
import static com.openpms.db.Tables.STATUSES;
import static com.openpms.db.Tables.TYPES;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_PREFERENCES;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Date alerts and notification mail on the dev data set (scripts/dev.sh test). Work package #5 is set up per test;
 * the clock is passed in, so time zones and digest slots are exact. Mail goes to the dev Mailpit.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class JobsIT {
    @Autowired
    DSLContext db;
    @Autowired
    DateAlerts alerts;
    @Autowired
    MailJobs mail;

    UUID wp;
    UUID project;
    UUID junho;

    UUID user(String email) {
        return db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
    }

    List<String> alertsFor(UUID userId) {
        return db.select(NOTIFICATIONS.REASON, NOTIFICATIONS.ALERT_DATE).from(NOTIFICATIONS)
                .where(NOTIFICATIONS.WORK_PACKAGE_ID.eq(wp), NOTIFICATIONS.RECIPIENT_ID.eq(userId), NOTIFICATIONS.REASON.like("date_alert%"))
                .orderBy(NOTIFICATIONS.REASON).fetch(r -> r.value1() + " " + r.value2());
    }

    void dates(LocalDate start, LocalDate due) {
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.START_DATE, start).set(WORK_PACKAGES.DUE_DATE, due).where(WORK_PACKAGES.ID.eq(wp)).execute();
    }

    @BeforeEach
    void setUp() {
        var row = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(5L)).fetchOne();
        wp = row.getId();
        project = row.getProjectId();
        junho = user("junho.park@openpms.local");
        var open = db.select(STATUSES.ID).from(STATUSES).where(STATUSES.IS_CLOSED.isFalse()).limit(1).fetchOne(STATUSES.ID);
        var task = db.select(TYPES.ID).from(TYPES).where(TYPES.IS_MILESTONE.isFalse()).limit(1).fetchOne(TYPES.ID);
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.ASSIGNEE_ID, junho).setNull(WORK_PACKAGES.RESPONSIBLE_ID).set(WORK_PACKAGES.STATUS_ID, open)
                .set(WORK_PACKAGES.TYPE_ID, task).where(WORK_PACKAGES.ID.eq(wp)).execute();
        db.deleteFrom(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.eq(wp)).execute();
        db.deleteFrom(NOTIFICATIONS).where(NOTIFICATIONS.WORK_PACKAGE_ID.eq(wp)).execute();
        db.deleteFrom(NOTIFICATION_SETTINGS).where(NOTIFICATION_SETTINGS.USER_ID.eq(junho)).execute();
        db.update(USERS).set(USERS.TIME_ZONE, "Asia/Seoul").where(USERS.ID.eq(junho)).execute();
        db.update(USER_PREFERENCES).setNull(USER_PREFERENCES.REMINDERS_PAUSED_UNTIL).where(USER_PREFERENCES.USER_ID.eq(junho)).execute();
        // 이하늘 is a watcher and mention recipient here; NotificationSettingsIT changes their preferences in the same database
        var haneul = user("haneul.lee@openpms.local");
        db.update(USER_PREFERENCES).setNull(USER_PREFERENCES.REMINDERS_PAUSED_UNTIL).set(USER_PREFERENCES.IMMEDIATE_ON_MENTION, true)
                .where(USER_PREFERENCES.USER_ID.eq(haneul)).execute();
        db.deleteFrom(NOTIFICATION_SETTINGS).where(NOTIFICATION_SETTINGS.USER_ID.eq(haneul), NOTIFICATION_SETTINGS.PROJECT_ID.isNotNull()).execute();
    }

    @Test
    void startAndDueAlertsFollowThePersonsTimeZoneAndAreCreatedOnce() {
        dates(LocalDate.of(2030, 1, 11), LocalDate.of(2030, 1, 11));
        var kstMorning = Instant.parse("2030-01-10T00:30:00Z"); // 09:30 on the 10th in Seoul: both dates are tomorrow
        alerts.run(kstMorning);
        assertThat(alertsFor(junho)).containsExactly("date_alert_due 2030-01-11", "date_alert_start 2030-01-11");
        alerts.run(kstMorning);
        alerts.run(kstMorning.plus(Duration.ofHours(1)));
        assertThat(alertsFor(junho)).hasSize(2);

        // in Los Angeles it is still the 9th at that moment, and the 10th nine hours later
        db.deleteFrom(NOTIFICATIONS).where(NOTIFICATIONS.WORK_PACKAGE_ID.eq(wp)).execute();
        db.update(USERS).set(USERS.TIME_ZONE, "America/Los_Angeles").where(USERS.ID.eq(junho)).execute();
        alerts.run(kstMorning);
        assertThat(alertsFor(junho)).isEmpty();
        alerts.run(Instant.parse("2030-01-10T09:00:00Z"));
        assertThat(alertsFor(junho)).hasSize(2);
    }

    @Test
    void overdueOncePerDueDateAndANewDateGivesANewAlert() {
        // overdue alerts are off by default (V7): 박준호 turns them on
        db.insertInto(NOTIFICATION_SETTINGS).set(NOTIFICATION_SETTINGS.USER_ID, junho).set(NOTIFICATION_SETTINGS.OVERDUE_ALERT, true).execute();
        dates(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 8));
        alerts.run(Instant.parse("2030-01-10T01:00:00Z"));
        alerts.run(Instant.parse("2030-01-11T01:00:00Z"));
        assertThat(alertsFor(junho)).containsExactly("date_alert_overdue 2030-01-08");
        dates(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 9));
        alerts.run(Instant.parse("2030-01-11T01:00:00Z"));
        assertThat(alertsFor(junho)).containsExactly("date_alert_overdue 2030-01-08", "date_alert_overdue 2030-01-09");
    }

    @Test
    void milestonesClosedItemsSettingsPausesAndVisibility() {
        var now = Instant.parse("2030-01-10T00:30:00Z");
        dates(LocalDate.of(2030, 1, 11), LocalDate.of(2030, 1, 11));

        // a milestone has one date: the due-date alert only
        var milestone = db.select(TYPES.ID).from(TYPES).where(TYPES.IS_MILESTONE.isTrue()).limit(1).fetchOne(TYPES.ID);
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.TYPE_ID, milestone).where(WORK_PACKAGES.ID.eq(wp)).execute();
        alerts.run(now);
        assertThat(alertsFor(junho)).containsExactly("date_alert_due 2030-01-11");
        setUp();
        dates(LocalDate.of(2030, 1, 11), LocalDate.of(2030, 1, 11));

        // closed items get nothing
        var closed = db.select(STATUSES.ID).from(STATUSES).where(STATUSES.IS_CLOSED.isTrue()).limit(1).fetchOne(STATUSES.ID);
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.STATUS_ID, closed).where(WORK_PACKAGES.ID.eq(wp)).execute();
        alerts.run(now);
        assertThat(alertsFor(junho)).isEmpty();
        setUp();
        dates(LocalDate.of(2030, 1, 11), LocalDate.of(2030, 1, 11));

        // a project setting overrides the personal default: start alerts off here, due 3 days before
        db.insertInto(NOTIFICATION_SETTINGS).set(NOTIFICATION_SETTINGS.USER_ID, junho).set(NOTIFICATION_SETTINGS.PROJECT_ID, project)
                .setNull(NOTIFICATION_SETTINGS.START_DATE_ALERT_DAYS).set(NOTIFICATION_SETTINGS.DUE_DATE_ALERT_DAYS, (short) 3).execute();
        alerts.run(now);
        assertThat(alertsFor(junho)).isEmpty();
        alerts.run(Instant.parse("2030-01-08T00:30:00Z"));
        assertThat(alertsFor(junho)).containsExactly("date_alert_due 2030-01-11");
        setUp();
        dates(LocalDate.of(2030, 1, 11), LocalDate.of(2030, 1, 11));

        // paused reminders
        db.update(USER_PREFERENCES).set(USER_PREFERENCES.REMINDERS_PAUSED_UNTIL, LocalDate.of(2030, 1, 10)).where(USER_PREFERENCES.USER_ID.eq(junho)).execute();
        alerts.run(now);
        assertThat(alertsFor(junho)).isEmpty();

        // a watcher who cannot see the item (not in the project) gets nothing; one who can, gets the alerts
        var doyoon = user("doyoon.han@openpms.local");
        var haneul = user("haneul.lee@openpms.local");
        assertThat(db.fetchExists(MEMBERS, MEMBERS.PROJECT_ID.eq(project).and(MEMBERS.USER_ID.eq(doyoon)))).isFalse();
        db.insertInto(WATCHERS).set(WATCHERS.WORK_PACKAGE_ID, wp).set(WATCHERS.USER_ID, doyoon).execute();
        db.insertInto(WATCHERS).set(WATCHERS.WORK_PACKAGE_ID, wp).set(WATCHERS.USER_ID, haneul).execute();
        alerts.run(now);
        assertThat(alertsFor(doyoon)).isEmpty();
        assertThat(alertsFor(haneul)).hasSize(2);
    }

    @Test
    void digestSlotIsTheLatestTimeAtOrBeforeNowInThePersonsZone() {
        var times = List.of(LocalTime.of(17, 0), LocalTime.of(8, 0));
        assertThat(MailJobs.latestSlot(Instant.parse("2030-01-10T07:00:00Z"), "Asia/Seoul", times).toString())
                .isEqualTo("2030-01-10T08:00+09:00[Asia/Seoul]");
        assertThat(MailJobs.latestSlot(Instant.parse("2030-01-09T22:00:00Z"), "Asia/Seoul", times).toString())
                .isEqualTo("2030-01-09T17:00+09:00[Asia/Seoul]");
        assertThat(MailJobs.latestSlot(Instant.parse("2030-01-10T17:00:00Z"), "America/Los_Angeles", List.of()).toString())
                .isEqualTo("2030-01-10T08:00-08:00[America/Los_Angeles]");
    }

    @Test
    void mentionsAreMailedAfterAMinuteAndTheDigestOncePerSlot() {
        var now = Instant.now();
        var haneul = user("haneul.lee@openpms.local");
        UUID older = db.insertInto(NOTIFICATIONS).set(NOTIFICATIONS.RECIPIENT_ID, haneul).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp)
                .set(NOTIFICATIONS.PROJECT_ID, project).set(NOTIFICATIONS.ACTOR_ID, junho).set(NOTIFICATIONS.REASON, "mentioned")
                .set(NOTIFICATIONS.CREATED_AT, OffsetDateTime.ofInstant(now.minus(Duration.ofMinutes(2)), ZoneOffset.UTC))
                .returning(NOTIFICATIONS.ID).fetchOne().getId();
        UUID fresh = db.insertInto(NOTIFICATIONS).set(NOTIFICATIONS.RECIPIENT_ID, haneul).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp)
                .set(NOTIFICATIONS.PROJECT_ID, project).set(NOTIFICATIONS.ACTOR_ID, junho).set(NOTIFICATIONS.REASON, "mentioned")
                .set(NOTIFICATIONS.CREATED_AT, OffsetDateTime.ofInstant(now.minus(Duration.ofSeconds(10)), ZoneOffset.UTC))
                .returning(NOTIFICATIONS.ID).fetchOne().getId();
        assertThat(mail.mentions(now)).isGreaterThanOrEqualTo(1);
        assertThat(emailed(older)).isTrue();
        assertThat(emailed(fresh)).isFalse();

        // the digest takes what is left (the fresh mention) and runs once per slot
        db.update(USER_PREFERENCES).setNull(USER_PREFERENCES.LAST_DIGEST_AT).set(USER_PREFERENCES.DIGEST_ENABLED, true).where(USER_PREFERENCES.USER_ID.eq(haneul)).execute();
        mail.digests(now);
        assertThat(emailed(fresh)).isTrue();
        assertThat(db.select(USER_PREFERENCES.LAST_DIGEST_AT).from(USER_PREFERENCES).where(USER_PREFERENCES.USER_ID.eq(haneul)).fetchOne().value1()).isNotNull();
        assertThat(mail.digests(now)).isZero();

        // read notifications are never mailed
        UUID read = db.insertInto(NOTIFICATIONS).set(NOTIFICATIONS.RECIPIENT_ID, haneul).set(NOTIFICATIONS.WORK_PACKAGE_ID, wp)
                .set(NOTIFICATIONS.PROJECT_ID, project).set(NOTIFICATIONS.ACTOR_ID, junho).set(NOTIFICATIONS.REASON, "mentioned")
                .set(NOTIFICATIONS.CREATED_AT, OffsetDateTime.ofInstant(now.minus(Duration.ofMinutes(5)), ZoneOffset.UTC))
                .set(NOTIFICATIONS.READ_AT, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .returning(NOTIFICATIONS.ID).fetchOne().getId();
        mail.mentions(now);
        assertThat(emailed(read)).isFalse();
    }

    boolean emailed(UUID id) {
        return db.select(NOTIFICATIONS.EMAILED_AT).from(NOTIFICATIONS).where(NOTIFICATIONS.ID.eq(id)).fetchOne().value1() != null;
    }
}

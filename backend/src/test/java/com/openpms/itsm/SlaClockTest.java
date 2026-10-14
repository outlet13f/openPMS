package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlaClockTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final SlaClock.Calendar WEEKDAYS = SlaClock.Calendar.business(SEOUL, weekdays(LocalTime.of(9, 0), LocalTime.of(18, 0)));

    static List<SlaClock.Window> weekdays(LocalTime start, LocalTime end) {
        var out = new ArrayList<SlaClock.Window>();
        for (int d = 1; d <= 5; d++) out.add(new SlaClock.Window(DayOfWeek.of(d), start, end));
        return out;
    }

    static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
    }

    @Test void allDayCalendarJustAdds() {
        var cal = SlaClock.Calendar.always(SEOUL);
        assertThat(SlaClock.add(at("2026-10-17T23:00"), Duration.ofHours(4), cal, Set.of())).isEqualTo(at("2026-10-18T03:00"));
    }

    @Test void insideWorkingHoursStaysOnTheSameDay() {
        assertThat(SlaClock.add(at("2026-10-14T10:00"), Duration.ofHours(4), WEEKDAYS, Set.of())).isEqualTo(at("2026-10-14T14:00"));
    }

    @Test void theRestCarriesToTheNextWorkingMorning() {
        // 수 17:00 + 4h = 수 18:00까지 1h, 목 09:00부터 3h
        assertThat(SlaClock.add(at("2026-10-14T17:00"), Duration.ofHours(4), WEEKDAYS, Set.of())).isEqualTo(at("2026-10-15T12:00"));
    }

    @Test void fridayEveningSkipsTheWeekendAndAHoliday() {
        // 금 17:30 + 4h: 금 30분, 토·일 건너뜀, 월(휴무일) 건너뜀, 화 09:00부터 3h30m
        var holidays = Set.of(LocalDate.parse("2026-10-19"));
        assertThat(SlaClock.add(at("2026-10-16T17:30"), Duration.ofHours(4), WEEKDAYS, holidays)).isEqualTo(at("2026-10-20T12:30"));
    }

    @Test void receivedBeforeOpeningStartsAtOpening() {
        assertThat(SlaClock.add(at("2026-10-14T07:00"), Duration.ofMinutes(30), WEEKDAYS, Set.of())).isEqualTo(at("2026-10-14T09:30"));
    }

    @Test void receivedAtClosingStartsNextMorning() {
        assertThat(SlaClock.add(at("2026-10-14T18:00"), Duration.ofMinutes(30), WEEKDAYS, Set.of())).isEqualTo(at("2026-10-15T09:30"));
    }

    @Test void endingExactlyAtClosingStaysThatDay() {
        assertThat(SlaClock.add(at("2026-10-14T17:00"), Duration.ofHours(1), WEEKDAYS, Set.of())).isEqualTo(at("2026-10-14T18:00"));
    }

    @Test void splitDaysCountEachWindow() {
        var lunch = SlaClock.Calendar.business(SEOUL, List.of(
                new SlaClock.Window(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)),
                new SlaClock.Window(DayOfWeek.WEDNESDAY, LocalTime.of(13, 0), LocalTime.of(18, 0))));
        assertThat(SlaClock.add(at("2026-10-14T11:00"), Duration.ofHours(2), lunch, Set.of())).isEqualTo(at("2026-10-14T14:00"));
    }

    @Test void workingTimeCountsOnlyOpenHours() {
        // 금 17:00 ~ 화 10:00, 월 휴무: 금 1h + 화 1h
        var holidays = Set.of(LocalDate.parse("2026-10-19"));
        assertThat(SlaClock.workingTime(at("2026-10-16T17:00"), at("2026-10-20T10:00"), WEEKDAYS, holidays)).isEqualTo(Duration.ofHours(2));
    }

    @Test void workingTimeOfAnEmptyOrReversedSpanIsZero() {
        assertThat(SlaClock.workingTime(at("2026-10-14T10:00"), at("2026-10-14T10:00"), WEEKDAYS, Set.of())).isZero();
        assertThat(SlaClock.workingTime(at("2026-10-14T11:00"), at("2026-10-14T10:00"), WEEKDAYS, Set.of())).isZero();
    }

    @Test void addAndWorkingTimeAgree() {
        var start = at("2026-10-16T16:10");
        var due = SlaClock.add(start, Duration.ofMinutes(615), WEEKDAYS, Set.of());
        assertThat(SlaClock.workingTime(start, due, WEEKDAYS, Set.of())).isEqualTo(Duration.ofMinutes(615));
    }

    @Test void aBusinessCalendarWithoutHoursIsRejected() {
        var empty = SlaClock.Calendar.business(SEOUL, List.of());
        assertThatThrownBy(() -> SlaClock.add(at("2026-10-14T10:00"), Duration.ofHours(1), empty, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void parsesStoredHours() {
        var w = SlaClock.parseHours(List.of(Map.of("day", 1, "start", "09:00", "end", "18:00")));
        assertThat(w).containsExactly(new SlaClock.Window(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)));
    }

    @Test void rejectsAWindowThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> SlaClock.parseHours(List.of(Map.of("day", 1, "start", "18:00", "end", "09:00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

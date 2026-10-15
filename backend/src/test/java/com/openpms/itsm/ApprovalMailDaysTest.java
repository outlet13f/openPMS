package com.openpms.itsm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpms.common.Dates;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The reminder counts working days from the day after the first mail up to today, inclusive. 2026-10-16 is a Friday. */
class ApprovalMailDaysTest {
    static final LocalDate FRI = LocalDate.of(2026, 10, 16);
    final Dates.WorkCalendar plain = new Dates.WorkCalendar(List.of(1, 2, 3, 4, 5), Set.of());

    @Test void sameDayIsZero() {
        assertThat(ApprovalMail.workingDaysSince(FRI, FRI, plain)).isZero();
    }

    @Test void fridayToMondayIsOne() {
        assertThat(ApprovalMail.workingDaysSince(FRI, FRI.plusDays(3), plain)).isEqualTo(1);
    }

    @Test void fridayToTuesdayIsTwo() {
        assertThat(ApprovalMail.workingDaysSince(FRI, FRI.plusDays(4), plain)).isEqualTo(2);
    }

    @Test void fridayToWednesdayIsThree() {
        assertThat(ApprovalMail.workingDaysSince(FRI, FRI.plusDays(5), plain)).isEqualTo(3);
    }

    @Test void aHolidayOnTheMondayInBetweenDoesNotCount() {
        var cal = new Dates.WorkCalendar(List.of(1, 2, 3, 4, 5), Set.of(FRI.plusDays(3)));
        assertThat(ApprovalMail.workingDaysSince(FRI, FRI.plusDays(5), cal)).isEqualTo(2);
    }
}

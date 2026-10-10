package com.openpms.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Date formats changed by another app instance reach this one: they are read again from the database now and then. */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class DisplayFormatsReloadIT {
    @Autowired
    SettingsRepo settings;

    static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @Test
    void aChangeSavedByAnotherInstanceIsPickedUp() {
        Dates.forget();
        assertThat(Dates.format(DAY)).isEqualTo("2026.10.21");
        try {
            // what another instance's 관리 · 달력과 날짜 writes
            settings.put("calendar.date_format", "\"YYYY-MM-DD\"", null);
            settings.put("calendar.week_starts_on", "1", null);
            assertThat(Dates.format(DAY)).as("read at most every %d ms", Dates.RELOAD_MS).isEqualTo("2026.10.21");
            Dates.forget();
            assertThat(Dates.format(DAY)).isEqualTo("2026-10-21");
            assertThat(Dates.startOfWeek(DAY)).isEqualTo(LocalDate.of(2026, 10, 19));
        } finally {
            settings.put("calendar.date_format", "\"default\"", null);
            settings.put("calendar.week_starts_on", "7", null);
            Dates.forget();
        }
        assertThat(Dates.format(DAY)).isEqualTo("2026.10.21");
        assertThat(Dates.startOfWeek(DAY)).isEqualTo(LocalDate.of(2026, 10, 18));
    }
}

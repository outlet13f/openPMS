package com.openpms.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Working-day arithmetic (architecture.md §3.3 S1). A working day is one of the configured ISO weekdays
 * (Monday 1 … Sunday 7) that is not a holiday; with {@code ignore} every day counts.
 */
public final class Dates {
    private Dates() {}

    public record WorkCalendar(List<Integer> workingDays, Set<LocalDate> holidays) {
        public boolean isWorking(LocalDate d, boolean ignore) {
            return ignore || (workingDays.contains(d.getDayOfWeek().getValue()) && !holidays.contains(d));
        }
    }

    public static LocalDate today(String timeZone) {
        try {
            return LocalDate.now(ZoneId.of(timeZone));
        } catch (Exception e) {
            return LocalDate.now(ZoneId.of("Asia/Seoul"));
        }
    }

    public static int isoWeekday(LocalDate d) {
        return d.getDayOfWeek().getValue();
    }

    /** First working day on or after d. */
    public static LocalDate nextWorkingDay(LocalDate d, WorkCalendar cal, boolean ignore) {
        var x = d;
        for (int i = 0; i < 400 && !cal.isWorking(x, ignore); i++) x = x.plusDays(1);
        return x;
    }

    /** Working days from start to due, both included. */
    public static int countWorkingDays(LocalDate start, LocalDate due, WorkCalendar cal, boolean ignore) {
        if (due.isBefore(start)) return 0;
        int n = 0;
        for (var x = start; !x.isAfter(due); x = x.plusDays(1)) if (cal.isWorking(x, ignore)) n++;
        return n;
    }

    /** The date that makes [start, result] contain {@code duration} working days. */
    public static LocalDate dueFromDuration(LocalDate start, int duration, WorkCalendar cal, boolean ignore) {
        var x = nextWorkingDay(start, cal, ignore);
        int left = Math.max(1, duration) - 1;
        while (left > 0) {
            x = x.plusDays(1);
            if (cal.isWorking(x, ignore)) left--;
        }
        return x;
    }

    public static LocalDate startFromDuration(LocalDate due, int duration, WorkCalendar cal, boolean ignore) {
        var x = due;
        for (int i = 0; i < 400 && !cal.isWorking(x, ignore); i++) x = x.minusDays(1);
        int left = Math.max(1, duration) - 1;
        while (left > 0) {
            x = x.minusDays(1);
            if (cal.isWorking(x, ignore)) left--;
        }
        return x;
    }

    /** The n-th working day strictly after d (rule S5: start lag+1 working days after the predecessor ends). */
    public static LocalDate nthWorkingDayAfter(LocalDate d, int n, WorkCalendar cal, boolean ignore) {
        var x = d;
        int left = Math.max(1, n);
        while (left > 0) {
            x = x.plusDays(1);
            if (cal.isWorking(x, ignore)) left--;
        }
        return x;
    }

    /** Monday of the week that contains d (the "이번 주" filter). */
    public static LocalDate monday(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    /** "2026.10.09", as the screens show dates. */
    private static volatile java.time.format.DateTimeFormatter pattern = java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private static volatile boolean hour12 = false;
    private static volatile int weekStart = 7;

    /** The administrator's date format (DisplayFormats); 2026.10.21 until it is loaded. */
    public static void usePattern(String javaPattern) {
        pattern = java.time.format.DateTimeFormatter.ofPattern(javaPattern);
    }

    /** The administrator's clock (12 or 24 hours) and first weekday (ISO: Monday 1 … Sunday 7). */
    public static void useClock(boolean twelveHours, int isoWeekStart) {
        hour12 = twelveHours;
        weekStart = isoWeekStart;
    }

    /** Date and time as the screens write them: "2026.10.21 15:05" or "2026.10.21 오후 3:05". */
    public static String formatDateTime(java.time.ZonedDateTime t) {
        var time = hour12 ? (t.getHour() < 12 ? "오전 " : "오후 ") + (t.getHour() % 12 == 0 ? 12 : t.getHour() % 12) + ":%02d".formatted(t.getMinute())
                : "%02d:%02d".formatted(t.getHour(), t.getMinute());
        return format(t.toLocalDate()) + " " + time;
    }

    /** The first day of the week that contains d. */
    public static LocalDate startOfWeek(LocalDate d) {
        return d.minusDays((d.getDayOfWeek().getValue() - weekStart + 7) % 7);
    }

    public static String format(LocalDate d) {
        return d == null ? null : pattern.format(d);
    }
}

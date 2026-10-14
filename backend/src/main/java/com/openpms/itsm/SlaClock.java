package com.openpms.itsm;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SLA time (docs/superpowers/specs/2026-10-14-itsm-design.md §5): a 24x7 calendar counts every minute, a business
 * calendar only its weekly windows outside holidays. Pure: the caller passes the holidays (non_working_days).
 */
public final class SlaClock {
    /** About ten years of days: a business calendar always has a window, so this only stops a broken one. */
    private static final int MAX_DAYS = 3660;

    public record Window(DayOfWeek day, LocalTime start, LocalTime end) {
        public Window {
            if (!end.isAfter(start)) throw new IllegalArgumentException("근무 시간의 끝이 시작보다 늦어야 합니다.");
        }
    }

    public record Calendar(boolean allDay, ZoneId zone, List<Window> windows) {
        public static Calendar always(ZoneId zone) {
            return new Calendar(true, zone, List.of());
        }

        public static Calendar business(ZoneId zone, List<Window> windows) {
            return new Calendar(false, zone, List.copyOf(windows));
        }

        List<Window> on(DayOfWeek d) {
            return windows.stream().filter(w -> w.day() == d).sorted(Comparator.comparing(Window::start)).toList();
        }
    }

    private SlaClock() {}

    /** The moment {@code amount} of calendar time after {@code start}. */
    public static Instant add(Instant start, Duration amount, Calendar cal, Set<LocalDate> holidays) {
        if (amount.isNegative()) throw new IllegalArgumentException("음수 시간은 더할 수 없습니다.");
        if (cal.allDay()) return start.plus(amount);
        if (cal.windows().isEmpty()) throw new IllegalArgumentException("근무 시간이 없는 달력입니다.");
        var left = amount;
        ZonedDateTime cursor = start.atZone(cal.zone());
        for (int i = 0; i < MAX_DAYS; i++) {
            var day = cursor.toLocalDate();
            if (!holidays.contains(day)) {
                for (var w : cal.on(day.getDayOfWeek())) {
                    var open = day.atTime(w.start()).atZone(cal.zone());
                    var close = day.atTime(w.end()).atZone(cal.zone());
                    var from = cursor.isAfter(open) ? cursor : open;
                    if (!from.isBefore(close)) continue;
                    var room = Duration.between(from, close);
                    if (left.compareTo(room) <= 0) return from.plus(left).toInstant();
                    left = left.minus(room);
                    cursor = close;
                }
            }
            cursor = day.plusDays(1).atStartOfDay(cal.zone());
        }
        throw new IllegalStateException("SLA 기한을 계산할 수 없습니다.");
    }

    /** How much calendar time lies in [from, to). */
    public static Duration workingTime(Instant from, Instant to, Calendar cal, Set<LocalDate> holidays) {
        if (!to.isAfter(from)) return Duration.ZERO;
        if (cal.allDay()) return Duration.between(from, to);
        var total = Duration.ZERO;
        var end = to.atZone(cal.zone());
        ZonedDateTime cursor = from.atZone(cal.zone());
        for (int i = 0; i < MAX_DAYS && cursor.isBefore(end); i++) {
            var day = cursor.toLocalDate();
            if (!holidays.contains(day)) {
                for (var w : cal.on(day.getDayOfWeek())) {
                    var open = day.atTime(w.start()).atZone(cal.zone());
                    var close = day.atTime(w.end()).atZone(cal.zone());
                    var a = cursor.isAfter(open) ? cursor : open;
                    var b = end.isBefore(close) ? end : close;
                    if (a.isBefore(b)) total = total.plus(Duration.between(a, b));
                }
            }
            cursor = day.plusDays(1).atStartOfDay(cal.zone());
        }
        return total;
    }

    /** service_calendars.hours: [{"day": 1, "start": "09:00", "end": "18:00"}] with ISO days. */
    public static List<Window> parseHours(List<Map<String, Object>> hours) {
        var out = new ArrayList<Window>();
        for (var h : hours) {
            var day = ((Number) h.get("day")).intValue();
            if (day < 1 || day > 7) throw new IllegalArgumentException("요일은 1(월)부터 7(일)까지입니다.");
            out.add(new Window(DayOfWeek.of(day), LocalTime.parse((String) h.get("start")), LocalTime.parse((String) h.get("end"))));
        }
        return out;
    }
}

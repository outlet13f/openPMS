package com.openpms.wp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openpms.common.Dates.WorkCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Scheduling rules S1–S8, S11 (architecture.md §3.3) on a small graph; no database. */
class ScheduleTest {
    /** Monday–Friday with 한글날 (Fri 2026-10-09) off. */
    static final WorkCalendar CAL = new WorkCalendar(List.of(1, 2, 3, 4, 5), Set.of(LocalDate.parse("2026-10-09")));

    static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    static Schedule.Node node(long n, UUID parent, String start, String due, boolean manual) {
        var s = start == null ? null : d(start);
        var e = due == null ? null : d(due);
        Integer duration = s != null && e != null ? com.openpms.common.Dates.countWorkingDays(s, e, CAL, false) : null;
        return new Schedule.Node(UUID.randomUUID(), n, "#" + n, parent, false, s, e, duration, false, manual);
    }

    static Schedule.Input input(String start, String due, Integer duration, String... touched) {
        var in = new Schedule.Input();
        in.hasStart = start != null;
        in.start = start == null ? null : d(start);
        in.hasDue = due != null;
        in.due = due == null ? null : d(due);
        in.hasDuration = duration != null;
        in.duration = duration;
        in.touched = touched.length == 0 ? null : List.of(touched);
        return in;
    }

    @Test
    void durationCountsWorkingDaysOnly() {
        var w = node(1, null, null, null, true);
        var r = new Schedule(List.of(w), List.of(), CAL).resolve(w, false, false, input("2026-10-05", "2026-10-16", null));
        assertEquals(9, r.duration()); // two weeks minus the holiday
    }

    @Test
    void startPlusDurationGivesDueSkippingTheHoliday() {
        var w = node(1, null, "2026-10-01", "2026-10-02", true);
        var r = new Schedule(List.of(w), List.of(), CAL).resolve(w, false, false, input("2026-10-07", null, 3, "startDate", "duration"));
        assertEquals(d("2026-10-12"), r.due()); // Wed, Thu, (Fri off), Mon
    }

    @Test
    void milestoneIsOneDay() {
        var w = node(1, null, null, null, true);
        var r = new Schedule(List.of(w), List.of(), CAL).resolve(w, true, false, input("2026-10-23", "2026-10-30", null));
        assertEquals(r.start(), r.due());
        assertEquals(1, r.duration());
    }

    @Test
    void successorFollowsAndParentSpansChildren() {
        var parent = node(1, null, null, null, false);
        var a = node(2, parent.id, "2026-09-28", "2026-10-06", true);
        var b = node(4, parent.id, "2026-10-05", "2026-10-16", false);
        var g = new Schedule(List.of(parent, a, b), List.of(new Schedule.Link(a.id, b.id, 0)), CAL);
        var changes = g.propagate(List.of(a.id), null, false);
        assertEquals(d("2026-10-07"), b.start);
        assertEquals(d("2026-10-20"), b.due); // 9 working days kept, 10/9 skipped
        assertEquals(d("2026-09-28"), parent.start);
        assertEquals(d("2026-10-20"), parent.due);
        assertTrue(changes.stream().anyMatch(c -> c.node() == b && "predecessor_moved".equals(c.cause().get("kind"))));
    }

    @Test
    void dryRunLeavesNodesAlone() {
        var a = node(2, null, "2026-09-28", "2026-10-06", true);
        var b = node(4, null, "2026-10-05", "2026-10-16", false);
        var g = new Schedule(List.of(a, b), List.of(new Schedule.Link(a.id, b.id, 0)), CAL);
        var affected = g.propagate(List.of(a.id), null, true);
        assertEquals(1, affected.size());
        assertEquals(d("2026-10-05"), b.start);
    }

    @Test
    void cyclesAreDetected() {
        var a = node(1, null, null, null, true);
        var b = node(2, null, null, null, true);
        var child = node(3, a.id, null, null, true);
        var g = new Schedule(List.of(a, b, child), List.of(new Schedule.Link(a.id, b.id, 0)), CAL);
        assertTrue(g.wouldCycle(b.id, a.id)); // a → b → a
        assertTrue(g.wouldCycle(a.id, child.id)); // between ancestor and descendant
        assertFalse(g.wouldCycle(child.id, b.id));
    }

    @Test
    void calendarChangeKeepsStartAndDuration() {
        var w = node(1, null, "2026-10-05", "2026-10-08", true); // 4 working days
        var newCal = new WorkCalendar(List.of(1, 2, 3, 4, 5), Set.of(LocalDate.parse("2026-10-07")));
        var g = new Schedule(List.of(w), List.of(), newCal);
        var moved = g.rescheduleAll();
        assertEquals(1, moved.size());
        assertEquals(d("2026-10-09"), w.due);
    }
}

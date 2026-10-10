package com.openpms.wp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P1–P5 (architecture.md §3.4) without a database. */
class ProgressTest {
    static final Progress.Settings WORK = Progress.Settings.DEFAULT;
    static final Progress.Settings STATUS = new Progress.Settings("status", false, "weighted");
    static final BigDecimal H40 = new BigDecimal("40");

    @Test
    void workModeRemainingSetsThePercentAndThePercentSetsTheRemaining() {
        assertThat(Progress.derive(WORK, false, 0, H40, 30, new BigDecimal("20"), true, false).doneRatio()).isEqualTo(50);
        var v = Progress.derive(WORK, false, 0, H40, 25, null, false, true);
        assertThat(v.remainingHours()).isEqualByComparingTo("30");
        // nothing typed: values stay
        assertThat(Progress.derive(WORK, false, 0, H40, 25, new BigDecimal("7"), false, false).remainingHours()).isEqualByComparingTo("7");
    }

    @Test
    void statusModeFollowsTheStatusAndClosedMeansDone() {
        var v = Progress.derive(STATUS, false, 80, H40, 10, null, false, false);
        assertThat(v.doneRatio()).isEqualTo(80);
        assertThat(v.remainingHours()).isEqualByComparingTo("8");
        assertThat(Progress.locked(STATUS, false)).isTrue();
        var closed = new Progress.Settings("work", true, "weighted");
        var done = Progress.derive(closed, true, 0, H40, 10, new BigDecimal("30"), false, false);
        assertThat(done.doneRatio()).isEqualTo(100);
        assertThat(done.remainingHours()).isEqualByComparingTo("0");
        assertThat(Progress.locked(closed, false)).isFalse();
        assertThat(Progress.locked(closed, true)).isTrue();
    }

    @Test
    void totalsWeightedOrAveragedAndExcludedStatusesLeftOut() {
        var items = List.of(new Progress.Item(H40, new BigDecimal("20"), 50, false), new Progress.Item(new BigDecimal("10"), BigDecimal.ZERO, 100, false),
                new Progress.Item(new BigDecimal("100"), new BigDecimal("100"), 0, true));
        var w = Progress.totals(WORK, items);
        assertThat(w.workHours()).isEqualTo(50.0);
        assertThat(w.remainingHours()).isEqualTo(20.0);
        assertThat(w.doneRatio()).isEqualTo(60);
        assertThat(Progress.totals(new Progress.Settings("work", false, "simple_average"), items).doneRatio()).isEqualTo(75);
        // work without remaining work recorded: nothing done yet, not all of it
        var unrecorded = List.of(new Progress.Item(new BigDecimal("8"), null, null, false), new Progress.Item(new BigDecimal("8"), new BigDecimal("4"), 50, false));
        assertThat(Progress.totals(WORK, unrecorded).doneRatio()).isEqualTo(25);
        assertThat(Progress.totals(WORK, unrecorded).remainingHours()).isEqualTo(4.0);
    }
}

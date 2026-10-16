package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CicdStatesTest {
    static final OffsetDateTime T = OffsetDateTime.parse("2026-10-15T10:00:00Z");

    @Test void newerNoticeWins() {
        assertThat(CicdStates.replaces(null, null, "running", T)).isTrue();
        assertThat(CicdStates.replaces("running", T, "success", T.plusMinutes(5))).isTrue();
        assertThat(CicdStates.replaces("success", T.plusMinutes(5), "running", T)).isFalse();     // late, older notice
        assertThat(CicdStates.replaces("running", T, "failed", T)).isTrue();                       // same time: an end beats running
        assertThat(CicdStates.replaces("failed", T, "running", T)).isFalse();
        assertThat(CicdStates.replaces("running", T, "success", OffsetDateTime.parse("2026-10-15T18:59:00+09:00"))).isFalse(); // an earlier instant, other offset
        assertThat(CicdStates.replaces("running", T, "success", OffsetDateTime.parse("2026-10-15T19:00:00+09:00"))).isTrue();  // the same instant: the end wins
    }

    @Test void badgeDoesNotHideAFailure() {
        assertThat(CicdStates.summarize(List.of("success", "failed", "success"))).isEqualTo("failed");
        assertThat(CicdStates.summarize(List.of("success", "running"))).isEqualTo("running");
        assertThat(CicdStates.summarize(List.of("canceled", "canceled"))).isEqualTo("canceled");
        assertThat(CicdStates.summarize(List.of("canceled", "success"))).isEqualTo("success");
        assertThat(CicdStates.summarize(List.of())).isNull();
    }
}

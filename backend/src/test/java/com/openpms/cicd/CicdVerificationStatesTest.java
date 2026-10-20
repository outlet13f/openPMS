package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CicdVerificationStatesTest {
    static final OffsetDateTime T = OffsetDateTime.parse("2026-10-16T02:00:00Z");

    static CicdVerificationStates.Current current(String result, OffsetDateTime basis) {
        return new CicdVerificationStates.Current(UUID.randomUUID(), UUID.randomUUID(), result, "failed".equals(result) ? "사유" : "", null, T.plusHours(1), basis);
    }

    @Test void columns() {
        assertThat(CicdVerificationStates.column(null, T)).isEqualTo("waiting");
        assertThat(CicdVerificationStates.column(current("passed", T), T)).isEqualTo("passed");
        assertThat(CicdVerificationStates.column(current("failed", T), T)).isEqualTo("failed");
        assertThat(CicdVerificationStates.column(current("passed", T), T.plusSeconds(1))).isEqualTo("reverify");
        assertThat(CicdVerificationStates.column(current("failed", T), T.plusSeconds(1))).isEqualTo("reverify");
        // the same instant written with another offset is not newer
        assertThat(CicdVerificationStates.column(current("passed", T), OffsetDateTime.parse("2026-10-16T11:00:00+09:00"))).isEqualTo("passed");
        assertThat(CicdVerificationStates.column(current("passed", T), null)).isEqualTo("passed");
    }

    @Test void defaults() {
        var dev = new CicdVerificationStates.EnvironmentRow(UUID.randomUUID(), 1, false, false);
        var stg = new CicdVerificationStates.EnvironmentRow(UUID.randomUUID(), 2, false, false);
        var prod = new CicdVerificationStates.EnvironmentRow(UUID.randomUUID(), 3, true, false);
        var hiddenStg = new CicdVerificationStates.EnvironmentRow(stg.id(), 2, false, true);
        assertThat(CicdVerificationStates.defaultTarget(java.util.List.of(prod, dev, stg))).isEqualTo(prod.id());
        assertThat(CicdVerificationStates.defaultSource(java.util.List.of(prod, dev, stg), prod.id())).isEqualTo(stg.id());
        assertThat(CicdVerificationStates.defaultSource(java.util.List.of(dev, hiddenStg, prod), prod.id())).isEqualTo(dev.id());
        assertThat(CicdVerificationStates.defaultSource(java.util.List.of(prod), prod.id())).isNull();
        assertThat(CicdVerificationStates.defaultTarget(java.util.List.of(dev, stg))).isNull();
        assertThat(CicdVerificationStates.defaultTarget(java.util.List.of(dev, new CicdVerificationStates.EnvironmentRow(prod.id(), 3, true, true)))).isNull();
    }

    @Test void warnings() {
        assertThat(CicdVerificationStates.warning(null, T)).isEqualTo("unverified");
        assertThat(CicdVerificationStates.warning(current("passed", T), T)).isNull();
        assertThat(CicdVerificationStates.warning(current("failed", T), T)).isEqualTo("failed");
        assertThat(CicdVerificationStates.warning(current("passed", T), T.plusMinutes(1))).isEqualTo("stale");
        assertThat(CicdVerificationStates.warning(current("failed", T), T.plusMinutes(1))).isEqualTo("stale");
    }
}

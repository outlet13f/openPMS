package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A merged MR's merge commit is stored only as a full number (40 hex, or 64 for SHA-256): the development tab and the
 * first-deployment candidates join it to the commits by equality on (repository_id, sha), which a short number would miss.
 */
class MergeCommitTest {
    @Test void fullNumbersAreKeptInLowerCase() {
        assertThat(CicdProcessor.mergeCommit("E4C3A2B1D0F9E8D7C6B5A4938271605F4E3D2C1B ")).isEqualTo("e4c3a2b1d0f9e8d7c6b5a4938271605f4e3d2c1b");
        assertThat(CicdProcessor.mergeCommit("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    @Test void shortOrOddNumbersAreNotStored() {
        assertThat(CicdProcessor.mergeCommit("bcbb5ec3")).isNull();
        assertThat(CicdProcessor.mergeCommit("a".repeat(39))).isNull();
        assertThat(CicdProcessor.mergeCommit("a".repeat(41))).isNull();
        assertThat(CicdProcessor.mergeCommit("g".repeat(40))).isNull();
        assertThat(CicdProcessor.mergeCommit(null)).isNull();
    }
}

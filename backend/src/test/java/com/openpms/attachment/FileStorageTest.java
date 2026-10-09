package com.openpms.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStorageTest {
    @TempDir
    Path dir;

    @Test
    void sweepDeletesOnlyOldFilesNoRowPointsTo() throws Exception {
        var storage = new FileStorage(dir.toString());
        var now = Instant.now();
        for (var key : new String[] {"2026/09/kept", "2026/09/orphan", "2026/10/new-orphan"}) storage.put(key, new ByteArrayInputStream(new byte[] {1}));
        var leftoverTmp = Files.writeString(dir.resolve("2026/09/.upload-1.tmp"), "x");
        var old = FileTime.from(now.minus(Duration.ofDays(3)));
        Files.setLastModifiedTime(dir.resolve("2026/09/kept"), old);
        Files.setLastModifiedTime(dir.resolve("2026/09/orphan"), old);
        Files.setLastModifiedTime(leftoverTmp, old);

        assertThat(storage.sweep(Set.of("2026/09/kept"), now.minus(Duration.ofDays(1)))).isEqualTo(2);
        assertThat(dir.resolve("2026/09/kept")).exists();
        assertThat(dir.resolve("2026/09/orphan")).doesNotExist();
        assertThat(leftoverTmp).doesNotExist();
        assertThat(dir.resolve("2026/10/new-orphan")).exists(); // could be an upload whose row is not committed yet
    }

    @Test
    void keysCannotLeaveTheRoot() {
        var storage = new FileStorage(dir.toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.open("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }
}

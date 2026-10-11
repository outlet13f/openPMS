package com.openpms.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Requests run on virtual threads (spring.threads.virtual.enabled). On Java 21 a virtual thread that blocks inside
 * synchronized — on a database connection, a query, a file — keeps its carrier thread, and there are only as many
 * carriers as CPUs. The load check of the performance budget found exactly that: with two CPUs, two requests waiting for
 * a connection inside Snapshot's synchronized loader stopped everything until the pool timed out (30 s). Our own code
 * therefore uses java.util.concurrent locks, never synchronized.
 */
class NoPinningTest {
    private static final Pattern SYNCHRONIZED = Pattern.compile("\\bsynchronized\\b");

    @Test
    void theApplicationCodeNeverUsesSynchronized() throws Exception {
        var found = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var code = lines.get(i).replaceAll("//.*", "").trim();
                    if (code.startsWith("*") || code.startsWith("/*")) continue; // comments
                    if (SYNCHRONIZED.matcher(code).find()) found.add(file + ":" + (i + 1) + "  " + code);
                }
            }
        }
        assertThat(found).as("synchronized pins virtual threads on Java 21; use a ReentrantLock").isEmpty();
    }
}

package com.openpms.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Attachment bytes on a local volume (architecture.md §1 파일: /data/files). Keys are random and never contain the
 * original file name. Swapping in an S3-compatible store means replacing this class.
 */
@Component
public class FileStorage {
    private final Path root;

    public FileStorage(@Value("${openpms.files-dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** A new key such as 2026/10/4f…e1. */
    public String newKey() {
        var today = LocalDate.now();
        return "%04d/%02d/%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID());
    }

    private Path resolve(String key) {
        var p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("bad storage key");
        return p;
    }

    /** Write via a temp file in the same directory, then move, so a half-written file never has the real key. */
    public void put(String key, InputStream in) throws IOException {
        var target = resolve(key);
        Files.createDirectories(target.getParent());
        var tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
            // a leftover file is harmless; the row is gone
        }
    }
}

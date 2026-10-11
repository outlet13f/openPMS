package com.openpms.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Database dumps with pg_dump (custom format, restore with pg_restore) into openpms.backup.dir: one before every upgrade
 * that has migrations to apply (MigrateWithBackup), and on demand from 관리 · 시스템 정보. The newest openpms.backup.keep
 * dumps of each kind are kept. The image ships the PostgreSQL 17 client; where pg_dump is missing (development) a dump
 * fails with a clear message.
 */
@Component
public class Backups {
    private static final Logger log = LoggerFactory.getLogger(Backups.class);
    private static final Pattern FILE = Pattern.compile("^openpms-(before-upgrade|manual)-.*\\.dump$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    public record BackupFile(String name, String kind, long bytes, OffsetDateTime createdAt) {}

    /** host, port and database of a jdbc:postgresql URL */
    record Target(String host, int port, String database) {}

    public static class BackupException extends RuntimeException {
        BackupException(String message) {
            super(message);
        }
    }

    private final Path dir;
    private final boolean beforeUpgrade;
    private final int keep;
    private final String pgDump;
    private final String url;
    private final String user;
    private final String password;
    private final AtomicBoolean running = new AtomicBoolean();

    public Backups(@Value("${openpms.backup.dir:/data/backups}") String dir, @Value("${openpms.backup.before-upgrade:true}") boolean beforeUpgrade,
                   @Value("${openpms.backup.keep:5}") int keep, @Value("${openpms.backup.pg-dump:pg_dump}") String pgDump,
                   @Value("${spring.datasource.url}") String url, @Value("${spring.datasource.username}") String user,
                   @Value("${spring.datasource.password:}") String password) {
        this.dir = Path.of(dir);
        this.beforeUpgrade = beforeUpgrade;
        this.keep = Math.max(1, keep);
        this.pgDump = pgDump;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public boolean beforeUpgrade() {
        return beforeUpgrade;
    }

    public Path dir() {
        return dir;
    }

    public int keep() {
        return keep;
    }

    public boolean running() {
        return running.get();
    }

    static Target parse(String jdbcUrl) {
        var m = Pattern.compile("^jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?]+)").matcher(jdbcUrl == null ? "" : jdbcUrl);
        if (!m.find()) throw new BackupException("데이터베이스 주소를 읽을 수 없습니다: " + jdbcUrl);
        return new Target(m.group(1), m.group(2) == null ? 5432 : Integer.parseInt(m.group(2)), m.group(3));
    }

    /** pg_dump's version line, or null when it cannot be run (asked on each 시스템 정보 visit; quick). */
    public String pgDumpVersion() {
        try {
            var p = new ProcessBuilder(pgDump, "--version").redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** One dump; kind is "before-upgrade" or "manual", label goes into the file name. Fails (BackupException) rather than half-writes. */
    public BackupFile dump(String kind, String label) {
        if (!running.compareAndSet(false, true)) throw new BackupException("다른 백업이 진행 중입니다. 끝난 뒤 다시 시도하세요.");
        try {
            var t = parse(url);
            Files.createDirectories(dir);
            var name = "openpms-" + kind + "-" + label.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + LocalDateTime.now(ZoneOffset.UTC).format(STAMP) + ".dump";
            var part = dir.resolve(name + ".part");
            var pb = new ProcessBuilder(pgDump, "--format=custom", "--no-owner", "--host=" + t.host(), "--port=" + t.port(), "--username=" + user,
                    "--dbname=" + t.database(), "--file=" + part).redirectErrorStream(true);
            pb.environment().put("PGPASSWORD", password);
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new BackupException("pg_dump를 실행할 수 없습니다(" + pgDump + "). 운영 이미지에는 들어 있습니다: " + e.getMessage());
            }
            var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                Files.deleteIfExists(part);
                throw new BackupException("pg_dump가 30분 안에 끝나지 않았습니다.");
            }
            if (p.exitValue() != 0) {
                Files.deleteIfExists(part);
                var tail = output.length() > 500 ? output.substring(output.length() - 500) : output;
                throw new BackupException("pg_dump가 실패했습니다: " + tail.trim());
            }
            var file = dir.resolve(name);
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            log.info("Backup written: {} ({} bytes)", file, Files.size(file));
            prune(kind);
            return describe(file);
        } catch (IOException e) {
            throw new BackupException("백업 파일을 쓸 수 없습니다(" + dir + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("백업이 중단되었습니다.");
        } finally {
            running.set(false);
        }
    }

    private BackupFile describe(Path f) throws IOException {
        var m = FILE.matcher(f.getFileName().toString());
        var kind = m.matches() ? m.group(1) : "manual";
        return new BackupFile(f.getFileName().toString(), kind, Files.size(f),
                OffsetDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), ZoneOffset.UTC));
    }

    /** The dumps in the folder, newest first (an unreadable folder lists nothing). */
    public List<BackupFile> list() {
        if (!Files.isDirectory(dir)) return List.of();
        var out = new ArrayList<BackupFile>();
        try (Stream<Path> files = Files.list(dir)) {
            for (var f : files.filter(f -> FILE.matcher(f.getFileName().toString()).matches()).toList()) out.add(describe(f));
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparing(BackupFile::createdAt).reversed().thenComparing(BackupFile::name, Comparator.reverseOrder()));
        return out;
    }

    /** Keeps the newest `keep` dumps of a kind. */
    void prune(String kind) {
        var old = list().stream().filter(b -> b.kind().equals(kind)).skip(keep).toList();
        for (var b : old) {
            try {
                Files.deleteIfExists(dir.resolve(b.name()));
                log.info("Old backup removed: {}", b.name());
            } catch (IOException e) {
                log.warn("Could not remove old backup {}: {}", b.name(), e.getMessage());
            }
        }
    }
}

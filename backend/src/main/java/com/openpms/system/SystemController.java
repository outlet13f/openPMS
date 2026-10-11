package com.openpms.system;

import static com.openpms.db.Tables.ATTACHMENTS;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.common.ApiException;
import com.openpms.core.Current;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 · 시스템 정보 (administrators): what is running, the database and its migrations, the backups, and a backup on demand. */
@RestController
@RequestMapping("/api/v1/admin/system")
public class SystemController {
    public record App(String version, OffsetDateTime builtAt, OffsetDateTime startedAt, String java, boolean jobsEnabled) {}

    public record Database(String version, long sizeBytes) {}

    public record Migration(String version, String description, OffsetDateTime installedAt, boolean success) {}

    public record Migrations(String current, int applied, int pending, List<Migration> recent) {}

    public record BackupInfo(boolean beforeUpgrade, String dir, int keep, String pgDump, boolean running, List<Backups.BackupFile> files) {}

    public record Counts(long activeUsers, long projects, long workPackages, long attachmentBytes) {}

    public record Info(App app, Database database, Migrations migrations, BackupInfo backups, Counts counts) {}

    private final Current current;
    private final DSLContext db;
    private final Flyway flyway;
    private final Backups backups;
    private final ObjectProvider<BuildProperties> build;
    private final boolean jobs;

    public SystemController(Current current, DSLContext db, Flyway flyway, Backups backups, ObjectProvider<BuildProperties> build,
                            @Value("${openpms.jobs.enabled:true}") boolean jobs) {
        this.current = current;
        this.db = db;
        this.flyway = flyway;
        this.backups = backups;
        this.build = build;
        this.jobs = jobs;
    }

    private void admin() {
        if (!current.ctx().isAdmin()) throw ApiException.forbidden("관리자만 볼 수 있습니다.");
    }

    @GetMapping
    public Info info() {
        admin();
        var b = build.getIfAvailable();
        var app = new App(b == null ? "dev" : b.getVersion(), b == null || b.getTime() == null ? null : OffsetDateTime.ofInstant(b.getTime(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()), ZoneOffset.UTC),
                System.getProperty("java.version"), jobs);
        var database = new Database(String.valueOf(db.fetchValue("show server_version")),
                db.select(DSL.field("pg_database_size(current_database())", Long.class)).fetchOne().value1());
        var fi = flyway.info();
        var applied = Arrays.stream(fi.applied()).toList();
        var recent = applied.stream().sorted(Comparator.comparing((org.flywaydb.core.api.MigrationInfo m) -> m.getInstalledOn()).reversed()
                        .thenComparing(m -> m.getInstalledRank(), Comparator.reverseOrder()))
                .limit(8)
                .map(m -> new Migration(m.getVersion() == null ? "" : m.getVersion().getVersion(), m.getDescription(),
                        m.getInstalledOn() == null ? null : OffsetDateTime.ofInstant(m.getInstalledOn().toInstant(), ZoneOffset.UTC), m.getState().isApplied() && !m.getState().isFailed()))
                .toList();
        var migrations = new Migrations(fi.current() == null ? null : fi.current().getVersion().getVersion(), applied.size(), fi.pending().length, recent);
        var backupInfo = new BackupInfo(backups.beforeUpgrade(), backups.dir().toString(), backups.keep(), backups.pgDumpVersion(), backups.running(), backups.list());
        var bytes = db.select(DSL.coalesce(DSL.sum(ATTACHMENTS.BYTE_SIZE), DSL.inline(java.math.BigDecimal.ZERO))).from(ATTACHMENTS).fetchOne().value1();
        var counts = new Counts(db.fetchCount(USERS, USERS.STATUS.eq("active")), db.fetchCount(PROJECTS), db.fetchCount(WORK_PACKAGES), bytes.longValue());
        return new Info(app, database, migrations, backupInfo, counts);
    }

    /** 지금 백업: a dump into the backup folder now (one at a time). */
    @PostMapping("/backup")
    public ResponseEntity<Backups.BackupFile> backup() {
        admin();
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(backups.dump("manual", "manual"));
        } catch (Backups.BackupException e) {
            throw new ApiException(backups.running() ? 409 : 503, "백업하지 못했습니다.", e.getMessage());
        }
    }
}

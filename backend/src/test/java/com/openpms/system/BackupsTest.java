package com.openpms.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 업그레이드 전 자동 백업: the dump itself (a stand-in pg_dump), keeping the newest few, and the rule that a failed dump stops the upgrade. */
class BackupsTest {
    @TempDir
    Path dir;

    /** A pg_dump stand-in: writes its arguments into the --file it was given, or fails when asked to. */
    Path fakePgDump(boolean fail) throws Exception {
        var script = dir.resolve(fail ? "pg_dump_fail.sh" : "pg_dump_ok.sh");
        Files.writeString(script, fail
                ? "#!/bin/sh\necho 'connection refused' >&2\nexit 1\n"
                : "#!/bin/sh\nfor a in \"$@\"; do case \"$a\" in --file=*) f=\"${a#--file=}\";; esac; done\necho \"$@ pw=$PGPASSWORD\" > \"$f\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    Backups backups(Path pgDump, int keep) {
        return new Backups(dir.resolve("backups").toString(), true, keep, pgDump.toString(), "jdbc:postgresql://db.internal:5433/openpms?sslmode=disable", "openpms", "s3cret");
    }

    @Test
    void readsTheDatabaseAddress() {
        var t = Backups.parse("jdbc:postgresql://db:5432/openpms");
        assertThat(t).isEqualTo(new Backups.Target("db", 5432, "openpms"));
        assertThat(Backups.parse("jdbc:postgresql://pg.example/pms?ssl=true")).isEqualTo(new Backups.Target("pg.example", 5432, "pms"));
        assertThatThrownBy(() -> Backups.parse("jdbc:h2:mem:x")).isInstanceOf(Backups.BackupException.class);
    }

    @Test
    void dumpsWithTheConnectionDetailsAndKeepsTheNewestFew() throws Exception {
        var b = backups(fakePgDump(false), 3);
        var first = b.dump("manual", "manual");
        var written = Files.readString(dir.resolve("backups").resolve(first.name()));
        assertThat(written).contains("--format=custom").contains("--host=db.internal").contains("--port=5433").contains("--username=openpms")
                .contains("--dbname=openpms").contains("pw=s3cret");
        assertThat(first.name()).startsWith("openpms-manual-manual-").endsWith(".dump");
        for (int i = 0; i < 4; i++) {
            Thread.sleep(5);
            b.dump("manual", "manual");
        }
        b.dump("before-upgrade", "v16-to-v17");
        var files = b.list();
        assertThat(files.stream().filter(f -> f.kind().equals("manual"))).hasSize(3);
        assertThat(files.stream().filter(f -> f.kind().equals("before-upgrade"))).hasSize(1);
        assertThat(files.get(0).kind()).as("newest first").isEqualTo("before-upgrade");
        // no half-written file is ever listed
        assertThat(Files.list(dir.resolve("backups")).filter(f -> f.toString().endsWith(".part"))).isEmpty();
    }

    @Test
    void aFailedDumpSaysWhyAndLeavesNothing() throws Exception {
        var b = backups(fakePgDump(true), 3);
        assertThatThrownBy(() -> b.dump("manual", "manual")).isInstanceOf(Backups.BackupException.class).hasMessageContaining("connection refused");
        assertThat(b.list()).isEmpty();
        var missing = backups(dir.resolve("no-such-pg_dump"), 3);
        assertThatThrownBy(() -> missing.dump("manual", "manual")).hasMessageContaining("pg_dump를 실행할 수 없습니다");
        assertThat(missing.pgDumpVersion()).isNull();
    }

    MigrationInfo version(String v) {
        var m = mock(MigrationInfo.class);
        when(m.getVersion()).thenReturn(MigrationVersion.fromVersion(v));
        return m;
    }

    Flyway flyway(MigrationInfo current, MigrationInfo... pending) {
        var flyway = mock(Flyway.class);
        var info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(current);
        when(info.pending()).thenReturn(pending);
        return flyway;
    }

    @Test
    void anUpgradeBacksUpFirstAndAFailedBackupStopsIt() {
        var strategy = new MigrateWithBackup();
        var b = mock(Backups.class);
        when(b.beforeUpgrade()).thenReturn(true);

        // migrations to apply on an existing schema: dump, then migrate
        var upgrade = flyway(version("16"), version("17"));
        strategy.backupBeforeMigrate(b).migrate(upgrade);
        verify(b).dump("before-upgrade", "v16-to-v17");
        verify(upgrade).migrate();

        // a fresh install, or nothing to apply: no dump
        var fresh = flyway(null, version("1"), version("17"));
        var b2 = mock(Backups.class);
        when(b2.beforeUpgrade()).thenReturn(true);
        strategy.backupBeforeMigrate(b2).migrate(fresh);
        strategy.backupBeforeMigrate(b2).migrate(flyway(version("17")));
        verify(b2, never()).dump(anyString(), anyString());

        // the dump fails: nothing is migrated
        var b3 = mock(Backups.class);
        when(b3.beforeUpgrade()).thenReturn(true);
        doThrow(new Backups.BackupException("disk full")).when(b3).dump(anyString(), anyString());
        var stopped = flyway(version("16"), version("17"));
        assertThatThrownBy(() -> strategy.backupBeforeMigrate(b3).migrate(stopped)).hasMessageContaining("disk full").hasMessageContaining("OPENPMS_BACKUP_BEFORE_UPGRADE");
        verify(stopped, never()).migrate();

        // switched off: straight to the migration
        var b4 = mock(Backups.class);
        when(b4.beforeUpgrade()).thenReturn(false);
        var off = flyway(version("16"), version("17"));
        strategy.backupBeforeMigrate(b4).migrate(off);
        verify(b4, never()).dump(anyString(), anyString());
        verify(off).migrate();
    }
}

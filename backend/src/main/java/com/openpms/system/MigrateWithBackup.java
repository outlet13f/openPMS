package com.openpms.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 업그레이드 전 자동 백업: when the database already has a schema and this version brings migrations it has not run, dump it
 * first (Backups) and only then migrate. A failed dump stops the start-up before anything changes; the operator either
 * fixes the cause or sets OPENPMS_BACKUP_BEFORE_UPGRADE=false (having a dump of their own). A fresh install has nothing
 * to keep and migrates straight away.
 */
@Configuration
public class MigrateWithBackup {
    private static final Logger log = LoggerFactory.getLogger(MigrateWithBackup.class);

    @Bean
    public FlywayMigrationStrategy backupBeforeMigrate(Backups backups) {
        return flyway -> {
            var info = flyway.info();
            var pending = info.pending();
            var current = info.current();
            if (pending.length > 0 && current != null && backups.beforeUpgrade()) {
                var target = pending[pending.length - 1].getVersion().getVersion();
                log.info("{} migration(s) to apply (v{} -> v{}): backing up the database first", pending.length, current.getVersion().getVersion(), target);
                try {
                    backups.dump("before-upgrade", "v" + current.getVersion().getVersion() + "-to-v" + target);
                } catch (Backups.BackupException e) {
                    throw new IllegalStateException("업그레이드 전 백업에 실패해 마이그레이션을 하지 않았습니다. " + e.getMessage()
                            + " 원인을 고치거나, 따로 백업을 받은 뒤 OPENPMS_BACKUP_BEFORE_UPGRADE=false로 다시 시작하세요.", e);
                }
            }
            flyway.migrate();
        };
    }
}

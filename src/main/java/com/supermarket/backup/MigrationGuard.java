package com.supermarket.backup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Runs before Flyway updates the database at startup:
 * <ul>
 *   <li>when a backup file is given (pos.restore.file), the current database is backed up and then replaced by
 *       that backup;</li>
 *   <li>when the new version has database changes for an existing database, a backup is taken first. MySQL cannot
 *       undo a half-done schema change, so if that backup fails the program does not start (unless
 *       pos.backup.before-update=false).</li>
 * </ul>
 * With pos.restore.only=true the program stops after the restore (used by restore-backup.bat).
 */
@Configuration
public class MigrationGuard {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationGuard.class);

    @Bean
    public FlywayMigrationStrategy guardedMigration(
            DataSource dataSource,
            @Value("${pos.backup.dir:backups}") String backupDir,
            @Value("${pos.backup.before-update:true}") boolean backupBeforeUpdate,
            @Value("${pos.restore.file:}") String restoreFile
    ) {
        return flyway -> {
            try {
                Path directory = Path.of(backupDir);
                if (!restoreFile.isBlank()) {
                    restore(dataSource, directory, Path.of(restoreFile));
                } else if (backupBeforeUpdate) {
                    backupBeforeUpdate(flyway, dataSource, directory);
                }
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
            flyway.migrate();
        };
    }

    @Bean
    public ApplicationRunner stopAfterRestore(ApplicationContext context, @Value("${pos.restore.only:false}") boolean restoreOnly) {
        return args -> {
            if (restoreOnly) {
                LOG.info("Restore finished; stopping as requested (pos.restore.only=true).");
                System.exit(SpringApplication.exit(context, () -> 0));
            }
        };
    }

    private static void restore(DataSource dataSource, Path directory, Path file) throws Exception {
        DatabaseBackup.checkComplete(file);
        if (DatabaseBackup.hasTables(dataSource)) {
            Path safety = DatabaseBackup.newFile(directory, "before-restore");
            DatabaseBackup.dump(dataSource, safety, "before restoring " + file.getFileName());
            LOG.info("Current database saved to {} before the restore", safety.toAbsolutePath());
        }
        DatabaseBackup.restore(dataSource, file);
    }

    private static void backupBeforeUpdate(Flyway flyway, DataSource dataSource, Path directory) throws Exception {
        MigrationInfo[] pending = flyway.info().pending();
        if (pending.length == 0 || !DatabaseBackup.hasTables(dataSource)) {
            return;
        }
        MigrationInfo current = flyway.info().current();
        String from = current == null ? "old" : "v" + current.getVersion();
        String to = "v" + pending[pending.length - 1].getVersion();
        Path file = DatabaseBackup.newFile(directory, "before-update-" + from + "-to-" + to);
        try {
            DatabaseBackup.dump(dataSource, file, "before updating the database from " + from + " to " + to);
        } catch (Exception exception) {
            throw new IllegalStateException("The database was NOT updated: the backup before the update failed ("
                    + exception.getMessage() + "). Fix the problem (disk space, permissions on '" + directory.toAbsolutePath()
                    + "') and start again.", exception);
        }
    }
}

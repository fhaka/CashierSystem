package com.supermarket.service;

import com.supermarket.backup.DatabaseBackup;
import com.supermarket.model.BackupLog;
import com.supermarket.repository.BackupLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Full database backups (see {@link DatabaseBackup}): every night at 23:00 and on demand. Only the newest
 * pos.backup.keep regular backups are kept; backups taken before an update or a restore are never deleted.
 */
@Service
public class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    private static final String PREFIX = "cashier-system";

    private final DataSource dataSource;
    private final BackupLogRepository backupLogRepository;
    private final Path directory;
    private final int keep;

    public BackupService(DataSource dataSource, BackupLogRepository backupLogRepository,
                         @Value("${pos.backup.dir:backups}") String directory,
                         @Value("${pos.backup.keep:30}") int keep) {
        this.dataSource = dataSource;
        this.backupLogRepository = backupLogRepository;
        this.directory = Path.of(directory);
        this.keep = Math.max(1, keep);
    }

    public List<BackupLog> findRecentBackups() {
        return backupLogRepository.findTop10ByOrderByCreatedAtDesc();
    }

    public BackupLog runManualBackup() {
        return createBackup("manual");
    }

    @Scheduled(cron = "0 0 23 * * *", zone = "Europe/Tirane")
    public void runDailyBackup() {
        createBackup("automatic");
    }

    public BackupLog createBackup(String reason) {
        Path file = DatabaseBackup.newFile(directory, PREFIX);
        try {
            DatabaseBackup.dump(dataSource, file, reason);
            removeOldBackups();
            return backupLogRepository.save(new BackupLog(LocalDateTime.now(), file.toAbsolutePath().toString(), "SUCCESS",
                    reason + " backup created"));
        } catch (Exception exception) {
            LOG.error("Backup failed", exception);
            return backupLogRepository.save(new BackupLog(LocalDateTime.now(), file.toAbsolutePath().toString(), "FAILED",
                    String.valueOf(exception.getMessage())));
        }
    }

    private void removeOldBackups() throws IOException {
        List<Path> regular;
        try (Stream<Path> files = Files.list(directory)) {
            regular = files.filter(p -> p.getFileName().toString().matches(PREFIX + "-\\d{8}-\\d{6}\\.sql"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (Path old : regular.subList(Math.min(keep, regular.size()), regular.size())) {
            Files.deleteIfExists(old);
            LOG.info("Old backup removed: {}", old.getFileName());
        }
    }
}

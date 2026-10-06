package com.supermarket.service;

import com.supermarket.model.BackupLog;
import com.supermarket.repository.BackupLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class BackupService {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final BackupLogRepository backupLogRepository;

    public BackupService(JdbcTemplate jdbcTemplate, DataSource dataSource, BackupLogRepository backupLogRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.backupLogRepository = backupLogRepository;
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
        Path backupPath = Path.of("backups", "cashier-system-" + LocalDateTime.now().format(FILE_DATE) + ".sql");
        try {
            Files.createDirectories(backupPath.getParent());
            List<String> tables = findTables();
            try (BufferedWriter writer = Files.newBufferedWriter(backupPath, StandardCharsets.UTF_8)) {
                writer.write("-- Supermarket POS backup\n");
                writer.write("-- Type: " + reason + "\n");
                writer.write("-- Created at: " + LocalDateTime.now() + "\n\n");
                writer.write("SET FOREIGN_KEY_CHECKS=0;\n\n");
                for (String table : tables) {
                    writeTableBackup(writer, table);
                }
                writer.write("SET FOREIGN_KEY_CHECKS=1;\n");
            }
            return backupLogRepository.save(new BackupLog(LocalDateTime.now(), backupPath.toAbsolutePath().toString(), "SUCCESS", reason + " backup created"));
        } catch (Exception exception) {
            return backupLogRepository.save(new BackupLog(LocalDateTime.now(), backupPath.toAbsolutePath().toString(), "FAILED", exception.getMessage()));
        }
    }

    private List<String> findTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
                while (resultSet.next()) {
                    String table = resultSet.getString("TABLE_NAME");
                    if (!table.startsWith("sys_")) {
                        tables.add(table);
                    }
                }
            }
        }
        return tables;
    }

    private void writeTableBackup(BufferedWriter writer, String table) throws Exception {
        writer.write("-- Data for table `" + table + "`\n");
        jdbcTemplate.query("SELECT * FROM `" + table + "`", resultSet -> {
            try {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                while (resultSet.next()) {
                    writer.write("INSERT INTO `" + table + "` VALUES (");
                    for (int i = 1; i <= columnCount; i++) {
                        if (i > 1) {
                            writer.write(", ");
                        }
                        writer.write(toSqlValue(resultSet.getObject(i)));
                    }
                    writer.write(");\n");
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Could not write backup for table " + table, exception);
            }
            return null;
        });
        writer.write("\n");
    }

    private String toSqlValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof BigDecimal) {
            return value.toString();
        }
        return "'" + value.toString().replace("\\", "\\\\").replace("'", "''") + "'";
    }
}

package com.supermarket.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Complete, restorable copies of the database as one SQL file: every table's definition and all its rows, read
 * in one consistent snapshot so a backup taken during sales is never half old, half new. Works on plain JDBC, so
 * it can run before Flyway updates the schema. MySQL is written table by table; H2 (demo and tests) uses its own
 * SCRIPT command.
 *
 * <p>A file is only valid if it starts with {@link #HEADER} and ends with {@link #FOOTER}; it is written to a
 * temporary name first, so a crash never leaves a file that looks complete.
 */
public final class DatabaseBackup {

    public static final String HEADER = "-- Supermarket POS backup v2";
    public static final String FOOTER = "-- Backup complete";

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseBackup.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter SQL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final int ROWS_PER_INSERT = 200;

    private DatabaseBackup() {
    }

    /** backups/cashier-system-20261006-213000.sql, or with a prefix such as "before-update". */
    public static Path newFile(Path directory, String prefix) {
        return directory.resolve(prefix + "-" + LocalDateTime.now().format(FILE_DATE) + ".sql");
    }

    public static void dump(DataSource dataSource, Path file, String reason) throws SQLException, IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try (Connection connection = dataSource.getConnection()) {
            if (isH2(connection)) {
                dumpH2(connection, partial, reason);
            } else {
                dumpMySql(connection, partial, reason);
            }
        } catch (SQLException | IOException | RuntimeException exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        LOG.info("Database backup written to {}", file.toAbsolutePath());
    }

    /**
     * Replaces the whole database with the backup. Every table is dropped first, so tables added by a later
     * version do not stay behind; the backup brings back its own schema version and Flyway updates it afterwards.
     */
    public static void restore(DataSource dataSource, Path file) throws SQLException, IOException {
        checkComplete(file);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            if (isH2(connection)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP ALL OBJECTS");
                    statement.execute("RUNSCRIPT FROM '" + file.toAbsolutePath().toString().replace("'", "''") + "' CHARSET 'UTF-8'");
                }
            } else {
                dropAllMySqlTables(connection);
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new FileSystemResource(file), StandardCharsets.UTF_8));
            }
        }
        LOG.info("Database restored from {}", file.toAbsolutePath());
    }

    /** Refuses files from the old backup format and files cut short. */
    public static void checkComplete(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Backup file not found: " + file.toAbsolutePath());
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) {
            throw new IllegalArgumentException("Not a restorable backup (old format or another file): " + file.getFileName());
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                if (lines.get(i).startsWith(FOOTER)) {
                    return;
                }
                break;
            }
        }
        throw new IllegalArgumentException("Backup file is incomplete: " + file.getFileName());
    }

    public static boolean hasTables(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return !tables(connection).isEmpty();
        }
    }

    private static boolean isH2(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
    }

    private static void dumpH2(Connection connection, Path file, String reason) throws SQLException, IOException {
        Path body = file.resolveSibling(file.getFileName() + ".h2");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SCRIPT TO '" + body.toAbsolutePath().toString().replace("'", "''") + "' CHARSET 'UTF-8'");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeHeader(writer, reason, "H2");
            for (String line : Files.readAllLines(body, StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.write('\n');
            }
            writer.write(FOOTER + "\n");
        } finally {
            Files.deleteIfExists(body);
        }
    }

    private static void dumpMySql(Connection connection, Path file, String reason) throws SQLException, IOException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try (Statement statement = connection.createStatement();
             BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
            writeHeader(writer, reason, connection.getMetaData().getDatabaseProductName() + " "
                    + connection.getMetaData().getDatabaseProductVersion());
            writer.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\nSET UNIQUE_CHECKS=0;\n");
            writer.write("SET SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n\n");
            for (String table : tables(connection)) {
                writer.write("DROP TABLE IF EXISTS `" + table + "`;\n");
                try (ResultSet create = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    create.next();
                    writer.write(create.getString(2) + ";\n");
                }
                writeRows(statement, writer, table);
                writer.write("\n");
            }
            writer.write("SET FOREIGN_KEY_CHECKS=1;\nSET UNIQUE_CHECKS=1;\n");
            writer.write(FOOTER + "\n");
            connection.commit();
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void writeHeader(BufferedWriter writer, String reason, String database) throws IOException {
        writer.write(HEADER + "\n");
        writer.write("-- Reason: " + reason + "\n");
        writer.write("-- Created: " + LocalDateTime.now().format(SQL_DATE_TIME) + "\n");
        writer.write("-- Database: " + database + "\n\n");
    }

    private static void writeRows(Statement statement, BufferedWriter writer, String table) throws SQLException, IOException {
        try (ResultSet rows = statement.executeQuery("SELECT * FROM `" + table + "`")) {
            ResultSetMetaData meta = rows.getMetaData();
            StringBuilder columns = new StringBuilder();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.append(i > 1 ? ", " : "").append('`').append(meta.getColumnName(i)).append('`');
            }
            int inBatch = 0;
            while (rows.next()) {
                writer.write(inBatch == 0 ? "INSERT INTO `" + table + "` (" + columns + ") VALUES\n(" : ",\n(");
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    if (i > 1) {
                        writer.write(", ");
                    }
                    writer.write(sqlValue(rows.getObject(i)));
                }
                writer.write(")");
                if (++inBatch == ROWS_PER_INSERT) {
                    writer.write(";\n");
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                writer.write(";\n");
            }
        }
    }

    static String sqlValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return bytes.length == 0 ? "''" : "X'" + HexFormat.of().formatHex(bytes) + "'";
        }
        if (value instanceof LocalDateTime dateTime) {
            return "'" + dateTime.format(SQL_DATE_TIME) + "'";
        }
        // Line breaks are written as \n so every row stays on one line: the restore drops lines starting with
        // "--" as comments, and a receipt's divider lines would otherwise be lost.
        String text = value.toString()
                .replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\0", "\\0")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "'" + text + "'";
    }

    private static List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        // H2 2.x calls ordinary tables "BASE TABLE", MySQL "TABLE".
        try (ResultSet resultSet = connection.getMetaData().getTables(connection.getCatalog(), null, "%", null)) {
            while (resultSet.next()) {
                String type = resultSet.getString("TABLE_TYPE");
                String schema = resultSet.getString("TABLE_SCHEM");
                boolean table = "TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type);
                if (table && (schema == null || !schema.equalsIgnoreCase("INFORMATION_SCHEMA"))) {
                    tables.add(resultSet.getString("TABLE_NAME"));
                }
            }
        }
        return tables;
    }

    private static void dropAllMySqlTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables(connection)) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}

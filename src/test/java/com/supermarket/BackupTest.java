package com.supermarket;

import com.supermarket.backup.DatabaseBackup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"pos.backup.dir=target/test-backups", "pos.backup.keep=2"})
class BackupTest extends IntegrationTest {

    private static final Path DIR = Path.of("target/test-backups");

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyBackupFolder() throws IOException {
        if (Files.isDirectory(DIR)) {
            try (Stream<Path> files = Files.walk(DIR)) {
                files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(DIR)).forEach(p -> p.toFile().delete());
            }
        }
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.sorted().toList();
        }
    }

    @Test
    void backupCanBeRestoredExactly() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "920000000001", "99.90", 7);
        jdbc.update("UPDATE products SET name = ? WHERE id = ?", "Djathë 'Korça' \\ 100% -- ; test", product);

        postJson("/backups/run", admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"));
        Path file = backups().get(0);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith(DatabaseBackup.HEADER).containsIgnoringCase("products").endsWith(DatabaseBackup.FOOTER + "\n");

        jdbc.update("UPDATE products SET stock = 0, name = 'changed'");
        jdbc.update("DELETE FROM shop_settings");
        jdbc.update("INSERT INTO shop_settings (setting_key, setting_value) VALUES ('name', 'After the backup')");

        DatabaseBackup.restore(dataSource, file);

        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, product))
                .isEqualTo("Djathë 'Korça' \\ 100% -- ; test");
        assertThat(stockOf(product)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_settings", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashiers", Integer.class)).isEqualTo(1);
        // The schema history comes back too, so Flyway knows the version of the restored data.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isPositive();
        // Still a working database: new rows get new ids.
        createProduct(login("admin", "admin-pass"), "920000000002", "10.00", 1);
    }

    @Test
    void onlyCompleteBackupsInTheNewFormatAreAccepted() throws Exception {
        Files.createDirectories(DIR);
        Path old = DIR.resolve("old.sql");
        Files.writeString(old, "-- Supermarket POS backup\nINSERT INTO `products` VALUES (1);\n");
        assertThatThrownBy(() -> DatabaseBackup.checkComplete(old)).hasMessageContaining("old format");

        Path cut = DIR.resolve("cut.sql");
        Files.writeString(cut, DatabaseBackup.HEADER + "\nCREATE TABLE x (id INT);\nINSERT INTO x VALUES\n(1),\n(2");
        assertThatThrownBy(() -> DatabaseBackup.checkComplete(cut)).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> DatabaseBackup.checkComplete(DIR.resolve("missing.sql"))).hasMessageContaining("not found");
    }

    @Test
    void onlyTheNewestRegularBackupsAreKept() throws Exception {
        String admin = registerSuperAdmin();
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve("before-update-v8-to-v9-20260101-000000.sql"), "kept");
        Files.writeString(DIR.resolve("cashier-system-20200101-000000.sql"), "oldest");
        Files.writeString(DIR.resolve("cashier-system-20200102-000000.sql"), "older");

        postJson("/backups/run", admin, null).andExpect(jsonPath("$.data.status").value("SUCCESS"));

        List<String> names = backups().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).hasSize(3).contains("before-update-v8-to-v9-20260101-000000.sql", "cashier-system-20200102-000000.sql")
                .doesNotContain("cashier-system-20200101-000000.sql");
        assertThat(names).noneMatch(n -> n.endsWith(".partial"));
    }
}

package com.supermarket.backup;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBackupTest {

    @Test
    void valuesAreWrittenAsMySqlLiteralsOnOneLine() {
        assertThat(DatabaseBackup.sqlValue(null)).isEqualTo("NULL");
        assertThat(DatabaseBackup.sqlValue(true)).isEqualTo("1");
        assertThat(DatabaseBackup.sqlValue(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(DatabaseBackup.sqlValue(new byte[] {1, (byte) 0xAB})).isEqualTo("X'01ab'");
        assertThat(DatabaseBackup.sqlValue(LocalDateTime.of(2026, 10, 6, 21, 5, 3, 120_000_000)))
                .isEqualTo("'2026-10-06 21:05:03.120000'");
        assertThat(DatabaseBackup.sqlValue("Djathë 'Korça' C:\\dir"))
                .isEqualTo("'Djathë ''Korça'' C:\\\\dir'");
        // A receipt: its divider lines must not start a line in the file, or the restore would take them for comments.
        String receipt = DatabaseBackup.sqlValue("SHOP\r\n--------\nTOTAL 10.00\n");
        assertThat(receipt).doesNotContain("\n").doesNotContain("\r").isEqualTo("'SHOP\\r\\n--------\\nTOTAL 10.00\\n'");
    }
}

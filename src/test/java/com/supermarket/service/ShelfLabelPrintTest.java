package com.supermarket.service;

import com.supermarket.dto.Warehouse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShelfLabelPrintTest {

    private static final byte[] CUT = {0x1D, 0x56, 0x00};

    private static int count(byte[] data, byte[] part) {
        int n = 0;
        for (int i = 0; i + part.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < part.length && match; j++) {
                match = data[i + j] == part[j];
            }
            if (match) {
                n++;
            }
        }
        return n;
    }

    @Test
    void validEan13CodesPrintAsEan13OthersAsCode128() throws Exception {
        assertThat(ThermalPrinterService.ean13Valid("5901234123457")).isTrue();
        assertThat(ThermalPrinterService.ean13Valid("5901234123458")).isFalse();

        byte[] ean = ThermalPrinterService.buildLabels(List.of(new Warehouse.Label("Ujë", null, new BigDecimal("60"), null,
                "5901234123457", 1)), "Haka Market", 32, LocalDate.of(2026, 10, 9));
        assertThat(count(ean, new byte[] {0x1D, 0x6B, 67, 13})).isEqualTo(1);

        byte[] other = ThermalPrinterService.buildLabels(List.of(new Warehouse.Label("Bukë", null, new BigDecimal("80"), null,
                "2000123", 1)), "Haka Market", 32, LocalDate.of(2026, 10, 9));
        assertThat(count(other, new byte[] {0x1D, 0x6B, 73, 9, '{', 'B'})).isEqualTo(1);
    }

    @Test
    void everyCopyIsCutOffAndThePriceIsAsLargeAsTheLineAllows() throws Exception {
        Warehouse.Label label = new Warehouse.Label("Djathë i bardhë", "Kuti 6", new BigDecimal("1250.00"), "për kg 1250", "2000123", 3);
        byte[] data = ThermalPrinterService.buildLabels(List.of(label), "Haka Market", 32, LocalDate.of(2026, 10, 9));
        String text = new String(data, Charset.forName("CP437"));

        assertThat(count(data, CUT)).isEqualTo(3);
        // "1250 LEK" is 8 characters: 4 times as large fills the 32-character line.
        assertThat(count(data, new byte[] {0x1D, 0x21, 0x33})).isEqualTo(3);
        assertThat(text).contains("Djathë i bardhë").contains("Kuti 6").contains("1250 LEK").contains("për kg 1250")
                .contains("Haka Market  09.10.2026");
    }
}

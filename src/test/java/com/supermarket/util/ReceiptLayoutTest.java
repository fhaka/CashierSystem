package com.supermarket.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptLayoutTest {

    @Test
    void rowsAlignTheValueToTheRightEdge() {
        assertThat(new ReceiptLayout(20).row("Totali", new BigDecimal("12.5")).toString())
                .isEqualTo("Totali         12.50\n");
        assertThat(new ReceiptLayout(20).row("  2 x 1.00", "2.00").toString())
                .isEqualTo("  2 x 1.00      2.00\n");
    }

    @Test
    void longLabelsWrapBetweenWordsAndTheValueGoesOnTheLastLine() {
        assertThat(new ReceiptLayout(20).row("Djathe i bardhe lope Korca", "1250.00").toString())
                .isEqualTo("Djathe i bardhe lope\nKorca        1250.00\n");
        assertThat(new ReceiptLayout(20).row("Para ne dore 10.00 EUR", "957.00").toString())
                .isEqualTo("Para ne dore 10.00\nEUR           957.00\n");
        assertThat(new ReceiptLayout(16).row("Pagese me karte", "1000.00").toString())
                .isEqualTo("Pagese me karte\n         1000.00\n");
    }

    @Test
    void centeredAndOverlongTextFitsTheWidth() {
        assertThat(new ReceiptLayout(10).center("ABC").toString()).isEqualTo("   ABC\n");
        assertThat(new ReceiptLayout(5).line("ABCDEFGHIJKL").toString()).isEqualTo("ABCDE\nFGHIJ\nKL\n");
        assertThat(new ReceiptLayout(4).rule().doubleRule().toString()).isEqualTo("----\n====\n");
    }
}

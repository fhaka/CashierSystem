package com.supermarket.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountStrategyTest {

    private final DiscountStrategy strategy = new DiscountStrategy(true, new BigDecimal("20000.00"), BigDecimal.TEN);

    @Test
    void noDiscountUpToTheThreshold() {
        assertThat(strategy.apply(List.of(), new BigDecimal("20000.00"))).isEqualByComparingTo("20000.00");
        assertThat(strategy.apply(List.of(), new BigDecimal("150.555"))).isEqualByComparingTo("150.56");
    }

    @Test
    void tenPercentOffAboveTheThreshold() {
        assertThat(strategy.apply(List.of(), new BigDecimal("30000.00"))).isEqualByComparingTo("27000.00");
    }
}

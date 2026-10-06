package com.supermarket.strategy;

import com.supermarket.model.CartItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Automatic discount on large purchases: above the threshold, the total gets the configured percentage off.
 * Each shop sets it in application.properties (pos.discount.*) or turns it off.
 */
@Component
public class DiscountStrategy implements PricingStrategy {

    private final boolean enabled;
    private final BigDecimal threshold;
    private final BigDecimal rate;

    public DiscountStrategy(
            @Value("${pos.discount.enabled:true}") boolean enabled,
            @Value("${pos.discount.threshold:20000.00}") BigDecimal threshold,
            @Value("${pos.discount.percent:10}") BigDecimal percent
    ) {
        this.enabled = enabled;
        this.threshold = threshold;
        this.rate = percent.movePointLeft(2);
    }

    @Override
    public BigDecimal apply(List<CartItem> cartItems, BigDecimal currentTotal) {
        if (!enabled || currentTotal.compareTo(threshold) <= 0) {
            return currentTotal.setScale(2, RoundingMode.HALF_UP);
        }
        return currentTotal.subtract(currentTotal.multiply(rate)).setScale(2, RoundingMode.HALF_UP);
    }
}

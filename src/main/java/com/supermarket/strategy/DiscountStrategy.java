package com.supermarket.strategy;

import com.supermarket.model.CartItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class DiscountStrategy implements PricingStrategy {

    private static final BigDecimal DISCOUNT_THRESHOLD = new BigDecimal("20000.00");
    private static final BigDecimal DISCOUNT_RATE = new BigDecimal("0.10");

    @Override
    public BigDecimal apply(List<CartItem> cartItems, BigDecimal currentTotal) {
        if (currentTotal.compareTo(DISCOUNT_THRESHOLD) <= 0) {
            return currentTotal.setScale(2, RoundingMode.HALF_UP);
        }
        return currentTotal.subtract(currentTotal.multiply(DISCOUNT_RATE)).setScale(2, RoundingMode.HALF_UP);
    }
}

package com.supermarket.strategy;

import com.supermarket.model.CartItem;

import java.math.BigDecimal;
import java.util.List;

public interface PricingStrategy {
    BigDecimal apply(List<CartItem> cartItems, BigDecimal currentTotal);
}

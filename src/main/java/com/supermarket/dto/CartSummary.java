package com.supermarket.dto;

import com.supermarket.model.Customer;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the customer will pay for the cart on screen, with every discount the checkout will apply,
 * and the customer attached to it (their points are worth pointsValue LEK).
 */
public record CartSummary(
        BigDecimal subtotal,
        BigDecimal promotionDiscount,
        BigDecimal manualDiscountPercent,
        BigDecimal manualDiscount,
        BigDecimal otherDiscount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        List<LinePromotion> promotions,
        Customer customer,
        BigDecimal pointsValue
) {

    public record LinePromotion(Long productId, String productName, String promotionName, BigDecimal discount) {
    }
}

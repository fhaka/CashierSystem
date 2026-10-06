package com.supermarket.dto;

import java.math.BigDecimal;

/** What the customer will pay for the cart on screen, with the discount the checkout will apply. */
public record CartSummary(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal totalAmount) {
}

package com.supermarket.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the customer pays. Several payments can be combined (part card, part cash, cash in EUR...).
 * Without payments the sale is taken as paid with the exact amount in LEK cash.
 */
public record CheckoutRequest(List<PaymentRequest> payments) {

    public record PaymentRequest(String method, String currency, BigDecimal amount) {
    }
}

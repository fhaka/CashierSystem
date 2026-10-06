package com.supermarket.dto;

import java.math.BigDecimal;

/** type is IN (money put into the drawer) or OUT (money taken out). */
public record CashMovementRequest(String type, BigDecimal amount, String reason) {
}

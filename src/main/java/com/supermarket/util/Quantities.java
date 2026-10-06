package com.supermarket.util;

import com.supermarket.exception.ValidationException;

import java.math.BigDecimal;

/** Quantity rules shared by the till, stock and purchases: pieces are whole numbers, kilograms have up to 3 decimals. */
public final class Quantities {

    public static final String PIECES = "pcs";
    public static final String KILOGRAMS = "kg";
    private static final BigDecimal MAX = new BigDecimal("99999");

    private Quantities() {
    }

    /** A quantity to sell or buy: greater than zero and valid for the unit. */
    public static BigDecimal requirePositive(BigDecimal quantity, String unit) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ValidationException("cart.quantityPositive");
        }
        return requireValidForUnit(quantity, unit);
    }

    /** A stock level: zero or more and valid for the unit. */
    public static BigDecimal requireStock(BigDecimal stock, String unit) {
        if (stock == null || stock.signum() < 0) {
            throw new ValidationException("product.stockNegative");
        }
        return requireValidForUnit(stock, unit);
    }

    private static BigDecimal requireValidForUnit(BigDecimal quantity, String unit) {
        if (quantity.compareTo(MAX) > 0) {
            throw new ValidationException("quantity.tooLarge", MAX.toPlainString());
        }
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (KILOGRAMS.equals(unit)) {
            if (normalized.scale() > 3) {
                throw new ValidationException("quantity.tooManyDecimals");
            }
        } else if (normalized.scale() > 0) {
            throw new ValidationException("quantity.wholeNumberRequired");
        }
        return quantity.setScale(3);
    }
}

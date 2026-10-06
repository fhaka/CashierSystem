package com.supermarket.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Barcodes printed by a weighing scale (EAN-13 starting with 2): {@code PP IIIII VVVVV C}, where PP is one of
 * the configured prefixes (pos.scale.prefixes, by default 21-29), IIIII the item code, VVVVV the weight in grams
 * (or the price in LEK, pos.scale.mode=PRICE) and C the check digit.
 * The product is registered with the first 7 digits (PP + IIIII) as its barcode.
 */
@Component
public class ScaleBarcodes {

    public enum Mode { WEIGHT, PRICE }

    /** What a scale barcode says: which product (its 7-digit barcode) and the weight in grams or price in LEK. */
    public record ScaleCode(String productBarcode, int value) {
    }

    private final List<String> prefixes;
    private final Mode mode;

    public ScaleBarcodes(
            @Value("${pos.scale.prefixes:21,22,23,24,25,26,27,28,29}") String prefixes,
            @Value("${pos.scale.mode:WEIGHT}") String mode
    ) {
        this.prefixes = Arrays.stream(prefixes.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
        this.mode = Mode.valueOf(mode.trim().toUpperCase());
    }

    public Mode mode() {
        return mode;
    }

    public Optional<ScaleCode> parse(String barcode) {
        if (barcode == null || !barcode.matches("\\d{13}") || prefixes.stream().noneMatch(barcode::startsWith)
                || !hasValidCheckDigit(barcode)) {
            return Optional.empty();
        }
        return Optional.of(new ScaleCode(barcode.substring(0, 7), Integer.parseInt(barcode.substring(7, 12))));
    }

    /** Quantity to sell: the weight in kg, or the price divided by the price per kg. */
    public BigDecimal quantity(ScaleCode code, BigDecimal pricePerUnit) {
        BigDecimal value = BigDecimal.valueOf(code.value());
        return mode == Mode.WEIGHT
                ? value.movePointLeft(3)
                : value.divide(pricePerUnit, 3, RoundingMode.HALF_UP);
    }

    static boolean hasValidCheckDigit(String ean13) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = ean13.charAt(i) - '0';
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10 == ean13.charAt(12) - '0';
    }
}

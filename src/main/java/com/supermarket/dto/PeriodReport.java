package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * X report (one shift, can be printed while it is open) or Z report (one day, all tills). Amounts in LEK.
 * openingCash and expectedCash are only filled for an X report.
 */
public record PeriodReport(
        String type,
        Long shiftId,
        LocalDateTime from,
        LocalDateTime to,
        int salesCount,
        BigDecimal grossSales,
        BigDecimal discounts,
        BigDecimal totalSales,
        List<ReceiptResponse.VatLine> vat,
        List<CurrencyLine> cashByCurrency,
        BigDecimal cashReceived,
        BigDecimal changeGiven,
        BigDecimal cashSales,
        BigDecimal cardSales,
        BigDecimal cashIn,
        BigDecimal cashOut,
        BigDecimal openingCash,
        BigDecimal expectedCash,
        List<CashierLine> byCashier,
        String printableText
) {

    /** Cash received in one currency, and its value in LEK at the rates used. */
    public record CurrencyLine(String currency, BigDecimal amount, BigDecimal amountLek) {
    }

    public record CashierLine(String cashierName, int salesCount, BigDecimal total) {
    }
}

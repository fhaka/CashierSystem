package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** A sale found by invoice number, with how much of each line can still be given back. */
public record SaleForRefund(
        Long saleId,
        String invoiceNumber,
        LocalDateTime date,
        String cashierName,
        BigDecimal totalAmount,
        List<Line> lines
) {

    public record Line(
            Long saleItemId,
            String productName,
            String unit,
            BigDecimal soldQuantity,
            BigDecimal refundedQuantity,
            BigDecimal refundableQuantity,
            BigDecimal unitPricePaid,
            BigDecimal lineTotal
    ) {
    }
}

package com.supermarket.dto;

import java.math.BigDecimal;

/** One receipt line. Discount, VAT and line total are the final amounts after the sale discount. */
public record ReceiptItemResponse(
        String productName,
        String barcode,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal unitPriceWithoutTax,
        BigDecimal taxRate,
        BigDecimal discountAmount,
        BigDecimal taxAmount,
        BigDecimal lineTotal
) {
}

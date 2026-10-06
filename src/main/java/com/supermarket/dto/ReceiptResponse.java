package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ReceiptResponse(
        Long saleId,
        String invoiceNumber,
        LocalDateTime date,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        List<ReceiptItemResponse> items,
        List<VatLine> vatSummary,
        String printableReceipt
) {

    /** VAT per rate, as required on a receipt: amount without VAT, VAT, and total for that rate. */
    public record VatLine(BigDecimal taxRate, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount) {
    }
}

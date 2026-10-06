package com.supermarket.dto;

import com.supermarket.model.RefundItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RefundResponse(
        Long id,
        String refundNumber,
        String invoiceNumber,
        LocalDateTime createdAt,
        String method,
        BigDecimal totalAmount,
        String reason,
        String cashierName,
        String approvedByName,
        List<RefundItem> items,
        String printableReceipt
) {
}

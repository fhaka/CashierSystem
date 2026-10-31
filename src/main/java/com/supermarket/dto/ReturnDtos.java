package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Requests and answers of the returns to suppliers. */
public final class ReturnDtos {

    private ReturnDtos() {
    }

    /** quantity in pieces or kg; price per piece/kg (empty: the last price from this supplier, else the average cost). */
    public record LineRequest(Long productId, BigDecimal quantity, BigDecimal price) {
    }

    /** kind: CREDIT or REPLACEMENT; reason: DAMAGED, EXPIRED, WRONG or OTHER (needs a note). */
    public record ReturnRequest(Long supplierId, String kind, String reason, String note, List<LineRequest> lines) {
    }

    public record LineRow(Long productId, String productName, String barcode, String unit, BigDecimal quantity, BigDecimal price,
                          BigDecimal taxRate, BigDecimal total) {
    }

    public record ReturnRow(Long id, String number, Long supplierId, String supplierName, String kind, String status, String reason,
                            String note, BigDecimal total, String createdBy, LocalDateTime createdAt, String replacedBy,
                            LocalDateTime replacedAt, List<LineRow> lines) {
    }
}

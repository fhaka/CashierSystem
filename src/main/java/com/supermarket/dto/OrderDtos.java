package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Requests and answers of the orders to suppliers. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** quantity: boxes when packageId is set, otherwise pieces/kg; price: per box or piece/kg (empty: the average cost). */
    public record LineRequest(Long productId, Long packageId, BigDecimal quantity, BigDecimal price) {
    }

    public record OrderRequest(Long supplierId, String note, List<LineRequest> lines) {
    }

    public record LineRow(Long productId, String productName, String barcode, String unit, Long packageId, String packageName,
                          Integer pieces, BigDecimal quantity, BigDecimal pieceQuantity, BigDecimal price, BigDecimal total,
                          BigDecimal stock) {
    }

    public record OrderRow(Long id, String number, String status, Long supplierId, String supplierName, String supplierPhone,
                           String note, int lineCount, BigDecimal total, String createdBy, LocalDateTime createdAt,
                           LocalDateTime sentAt, String closedBy, LocalDateTime closedAt, List<LineRow> lines) {
    }
}

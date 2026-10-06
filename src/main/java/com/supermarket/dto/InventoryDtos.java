package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Requests and answers of the inventory screens. */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /** quantityChange is negative when goods leave the shelf (damaged, expired, lost...). */
    public record AdjustmentRequest(Long productId, BigDecimal quantityChange, String reason, String note) {
    }

    public record ReorderLine(Long productId, String productName, String barcode, String unit, BigDecimal stock,
                              BigDecimal minStock, BigDecimal suggestedQuantity, BigDecimal lastPurchasePrice,
                              BigDecimal estimatedCost) {
    }

    /** Products to order from one supplier (the one they were last bought from). supplierId is null if unknown. */
    public record ReorderGroup(Long supplierId, String supplierName, List<ReorderLine> lines, BigDecimal estimatedTotal) {
    }

    /** A delivery that is probably still on the shelf and expires soon (or already did). */
    public record ExpiringLine(Long productId, String productName, String barcode, String unit, LocalDate expiryDate,
                               long daysLeft, BigDecimal estimatedOnShelf, String supplierName, String invoiceNumber) {
    }

    public record CountLineRequest(Long productId, String barcode, BigDecimal countedQuantity) {
    }

    public record CountLineView(Long productId, String productName, String barcode, String unit, BigDecimal countedQuantity,
                                BigDecimal currentStock, BigDecimal difference, LocalDateTime countedAt) {
    }

    public record CountView(Long id, String status, String note, LocalDateTime startedAt, String startedByName,
                            LocalDateTime finishedAt, String finishedByName, List<CountLineView> lines,
                            int linesWithDifference) {
    }
}

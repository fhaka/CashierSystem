package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A manual change of stock outside of sales and purchases, with its reason. */
@Entity
@Table(name = "stock_adjustments")
public class StockAdjustment {

    /**
     * OPENING_STOCK: the stock a product was created with (or, for older products, worked out once). SUPPLIER_RETURN:
     * goods given back to a supplier; REPLACEMENT: the same goods coming back for a replacement return. None is a loss.
     */
    public enum Reason { DAMAGED, EXPIRED, LOST, INTERNAL_USE, COUNT_CORRECTION, OTHER, OPENING_STOCK, SUPPLIER_RETURN, REPLACEMENT;

        /** Recorded by the program itself, never chosen by hand. */
        public boolean isAutomatic() {
            return this == COUNT_CORRECTION || this == OPENING_STOCK || this == SUPPLIER_RETURN || this == REPLACEMENT;
        }

        /** Stock that left or came in without being a loss or a find. */
        public boolean isTransfer() {
            return this == OPENING_STOCK || this == SUPPLIER_RETURN || this == REPLACEMENT;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @JsonIgnore
    private Product product;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    @JsonIgnore
    private Cashier cashier;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Negative when goods leave the shelf. */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityChange;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal stockAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    private String note;

    private Long inventoryCountId;

    /** Cost per unit when the stock changed, so a loss keeps the value of that day. Null for adjustments made before 1.4. */
    @Column(precision = 10, scale = 2)
    private BigDecimal unitCost;

    protected StockAdjustment() {
    }

    public StockAdjustment(Product product, Cashier cashier, BigDecimal quantityChange, Reason reason, String note,
                           Long inventoryCountId, LocalDateTime createdAt) {
        this.product = product;
        this.cashier = cashier;
        this.quantityChange = quantityChange;
        this.stockAfter = product.getStock();
        this.reason = reason;
        this.note = note;
        this.inventoryCountId = inventoryCountId;
        this.createdAt = createdAt;
        this.unitCost = product.getPurchasePrice();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return product.getId();
    }

    public String getProductName() {
        return product.getName();
    }

    public String getCashierName() {
        return cashier.getFullName();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getQuantityChange() {
        return quantityChange;
    }

    public BigDecimal getStockAfter() {
        return stockAfter;
    }

    public Reason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public Long getInventoryCountId() {
        return inventoryCountId;
    }
}

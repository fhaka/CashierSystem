package com.supermarket.model;

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

/** One change of a product's (or a box's) selling or purchase price. */
@Entity
@Table(name = "price_changes")
public class PriceChange {

    /** Where the change was made. */
    public enum Source { EDIT, PURCHASE, BULK, IMPORT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "package_id")
    private Long packageId;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id")
    private Cashier cashier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(precision = 10, scale = 2)
    private BigDecimal oldPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal newPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal oldPurchasePrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal newPurchasePrice;

    protected PriceChange() {
    }

    public PriceChange(Long productId, Long packageId, Cashier cashier, Source source, BigDecimal oldPrice, BigDecimal newPrice,
                       BigDecimal oldPurchasePrice, BigDecimal newPurchasePrice, LocalDateTime changedAt) {
        this.productId = productId;
        this.packageId = packageId;
        this.cashier = cashier;
        this.source = source;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.oldPurchasePrice = oldPurchasePrice;
        this.newPurchasePrice = newPurchasePrice;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getPackageId() {
        return packageId;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public String getCashierName() {
        return cashier == null ? null : cashier.getFullName();
    }

    public Source getSource() {
        return source;
    }

    public BigDecimal getOldPrice() {
        return oldPrice;
    }

    public BigDecimal getNewPrice() {
        return newPrice;
    }

    public BigDecimal getOldPurchasePrice() {
        return oldPurchasePrice;
    }

    public BigDecimal getNewPurchasePrice() {
        return newPurchasePrice;
    }
}

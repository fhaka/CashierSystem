package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Part of a sale line given back. Amount and VAT are the line's own share, so the discount is returned too. */
@Entity
@Table(name = "refund_items")
public class RefundItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false)
    @JsonIgnore
    private Refund refund;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_item_id", nullable = false)
    @JsonIgnore
    private SaleItem saleItem;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount;

    protected RefundItem() {
    }

    public RefundItem(SaleItem saleItem, BigDecimal quantity, BigDecimal amount, BigDecimal taxAmount) {
        this.saleItem = saleItem;
        this.quantity = quantity;
        this.amount = amount;
        this.taxRate = saleItem.getTaxRate();
        this.taxAmount = taxAmount;
    }

    void setRefund(Refund refund) {
        this.refund = refund;
    }

    public Long getSaleItemId() {
        return saleItem.getId();
    }

    public String getProductName() {
        return saleItem.getProduct().getName();
    }

    public String getUnit() {
        return saleItem.getUnit();
    }

    public SaleItem getSaleItem() {
        return saleItem;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }
}

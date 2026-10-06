package com.supermarket.model;

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
import java.time.LocalDateTime;

/** What was found on the shelf for one product. Counting it again replaces the number. */
@Entity
@Table(name = "inventory_count_lines")
public class InventoryCountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "count_id", nullable = false)
    private InventoryCount count;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal countedQuantity;

    @Column(nullable = false)
    private LocalDateTime countedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counted_by_id", nullable = false)
    private Cashier countedBy;

    protected InventoryCountLine() {
    }

    public InventoryCountLine(Product product, BigDecimal countedQuantity, Cashier countedBy, LocalDateTime countedAt) {
        this.product = product;
        this.countedQuantity = countedQuantity;
        this.countedBy = countedBy;
        this.countedAt = countedAt;
    }

    void setCount(InventoryCount count) {
        this.count = count;
    }

    public void recount(BigDecimal quantity, Cashier by, LocalDateTime at) {
        this.countedQuantity = quantity;
        this.countedBy = by;
        this.countedAt = at;
    }

    public Product getProduct() {
        return product;
    }

    public BigDecimal getCountedQuantity() {
        return countedQuantity;
    }

    public LocalDateTime getCountedAt() {
        return countedAt;
    }
}

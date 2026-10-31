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

/** One product given back: quantity in pieces or kg, at a price per piece/kg like on a purchase invoice. */
@Entity
@Table(name = "supplier_return_lines")
public class SupplierReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private SupplierReturn supplierReturn;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected SupplierReturnLine() {
    }

    public SupplierReturnLine(Product product, BigDecimal quantity, BigDecimal price, BigDecimal taxRate, BigDecimal lineTotal) {
        this.product = product;
        this.quantity = quantity;
        this.price = price;
        this.taxRate = taxRate;
        this.lineTotal = lineTotal;
    }

    void setSupplierReturn(SupplierReturn supplierReturn) {
        this.supplierReturn = supplierReturn;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}

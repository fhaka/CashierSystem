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
import java.math.RoundingMode;

/**
 * A box of one product with its own barcode, holding a number of pieces. It has no stock of its own: selling,
 * refunding or buying a box moves {@link #pieces} pieces of the product. Its price is its own if set (boxes are
 * often cheaper), otherwise pieces x the product's price.
 */
@Entity
@Table(name = "product_packages")
public class ProductPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, unique = true)
    private String barcode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int pieces;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active = true;

    protected ProductPackage() {
    }

    public ProductPackage(Product product, String barcode, String name, int pieces, BigDecimal price) {
        this.product = product;
        this.barcode = barcode;
        this.name = name;
        this.pieces = pieces;
        this.price = price;
    }

    /** What one box costs at the till now. */
    public BigDecimal effectivePrice() {
        return price != null ? price : product.getPrice().multiply(BigDecimal.valueOf(pieces)).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public Long getProductId() {
        return product.getId();
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPieces() {
        return pieces;
    }

    public void setPieces(int pieces) {
        this.pieces = pieces;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

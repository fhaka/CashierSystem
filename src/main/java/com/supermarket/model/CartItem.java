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
 * One line of a cart. Name, barcode, price, VAT and unit are copied from the product when it is scanned,
 * so the price the customer was shown does not change while they are at the till.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    @JsonIgnore
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private String barcode;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, length = 10)
    private String unit;

    protected CartItem() {
    }

    public CartItem(Product product, BigDecimal quantity) {
        this.productId = product.getId();
        this.productName = product.getName();
        this.barcode = product.getBarcode();
        this.quantity = quantity;
        this.price = product.getPrice();
        this.taxRate = product.getTaxRate();
        this.unit = product.getUnit();
    }

    public BigDecimal getLineTotal() {
        return price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getUnitPriceWithoutTax() {
        return withoutTax(price, taxRate);
    }

    public BigDecimal getTaxAmount() {
        BigDecimal lineTotal = getLineTotal();
        return lineTotal.subtract(withoutTax(lineTotal, taxRate));
    }

    /** Prices include VAT: the amount without VAT is price / (1 + rate). */
    public static BigDecimal withoutTax(BigDecimal amountWithTax, BigDecimal taxRate) {
        if (taxRate == null || taxRate.signum() == 0) {
            return amountWithTax.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(taxRate.movePointLeft(2));
        return amountWithTax.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    void setCart(Cart cart) {
        this.cart = cart;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getBarcode() {
        return barcode;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public String getUnit() {
        return unit;
    }
}

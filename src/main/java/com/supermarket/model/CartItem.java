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
 * <p>A line for a box (package) counts boxes: price is the price of one box and every box takes
 * {@link #getPiecesPerUnit()} pieces from the product's stock. A box and single pieces of the same product
 * are separate lines.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    @Column(name = "package_id")
    private Long packageId;

    @Column(length = 100)
    private String packageName;

    private Integer piecesPerUnit;

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

    /** A line of whole boxes. */
    public CartItem(ProductPackage box, BigDecimal boxes) {
        this(box.getProduct(), boxes);
        this.barcode = box.getBarcode();
        this.price = box.effectivePrice();
        this.packageId = box.getId();
        this.packageName = box.getName();
        this.piecesPerUnit = box.getPieces();
    }

    /** Pieces this line takes from the product's stock. */
    public BigDecimal getStockQuantity() {
        return piecesPerUnit == null ? quantity : quantity.multiply(BigDecimal.valueOf(piecesPerUnit));
    }

    /** The id of the line, used to change or remove it. */
    public Long getLineId() {
        return id;
    }

    public boolean isSameLine(Long productId, Long packageId) {
        return this.productId.equals(productId) && java.util.Objects.equals(this.packageId, packageId);
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

    public Long getPackageId() {
        return packageId;
    }

    public String getPackageName() {
        return packageName;
    }

    public Integer getPiecesPerUnit() {
        return piecesPerUnit;
    }
}

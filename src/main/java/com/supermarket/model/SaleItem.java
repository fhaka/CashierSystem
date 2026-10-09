package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

@Entity
@Table(name = "sale_items")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", nullable = false)
    @JsonIgnore
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceWithoutTax;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal taxAmount;

    @Column(nullable = false)
    private String unit;

    /** Cost price when sold, so profit reports do not change when the cost changes later. */
    @Column(precision = 10, scale = 2)
    private BigDecimal purchasePrice;

    /** This line's share of the sale discount. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** Amount paid for this line, after discount. VAT is calculated on this amount. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** The promotion that applied to this line, if any, and the part of the discount it gave. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id")
    @JsonIgnore
    private Promotion promotion;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal promotionDiscount = BigDecimal.ZERO;

    /** Set when the line was sold as boxes: which box, how many and at what price per box (quantity is pieces). */
    @Column(name = "package_id")
    private Long packageId;

    @Column(length = 100)
    private String packageName;

    @Column(precision = 12, scale = 3)
    private BigDecimal packageCount;

    @Column(precision = 10, scale = 2)
    private BigDecimal packagePrice;

    public SaleItem() {
    }

    public SaleItem(Product product, BigDecimal quantity, BigDecimal price, BigDecimal priceWithoutTax, BigDecimal taxRate, BigDecimal taxAmount, String unit) {
        this.product = product;
        this.quantity = quantity;
        this.price = price;
        this.priceWithoutTax = priceWithoutTax;
        this.taxRate = taxRate;
        this.taxAmount = taxAmount;
        this.unit = unit;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Sale getSale() {
        return sale;
    }

    public void setSale(Sale sale) {
        this.sale = sale;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
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

    public BigDecimal getPriceWithoutTax() {
        return priceWithoutTax;
    }

    public void setPriceWithoutTax(BigDecimal priceWithoutTax) {
        this.priceWithoutTax = priceWithoutTax;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }

    public Promotion getPromotion() {
        return promotion;
    }

    public void setPromotion(Promotion promotion) {
        this.promotion = promotion;
    }

    public String getPromotionName() {
        return promotion == null ? null : promotion.getName();
    }

    public BigDecimal getPromotionDiscount() {
        return promotionDiscount;
    }

    public void setPromotionDiscount(BigDecimal promotionDiscount) {
        this.promotionDiscount = promotionDiscount;
    }

    public Long getPackageId() {
        return packageId;
    }

    public String getPackageName() {
        return packageName;
    }

    public BigDecimal getPackageCount() {
        return packageCount;
    }

    public BigDecimal getPackagePrice() {
        return packagePrice;
    }

    public void setPackage(Long packageId, String packageName, BigDecimal packageCount, BigDecimal packagePrice) {
        this.packageId = packageId;
        this.packageName = packageName;
        this.packageCount = packageCount;
        this.packagePrice = packagePrice;
    }
}

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
import java.time.LocalDate;

@Entity
@Table(name = "purchase_items")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PurchaseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private PurchaseInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal purchasePrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPrice;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(nullable = false)
    private String unit;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** Best-before date of this delivery, if recorded. */
    private LocalDate expiryDate;

    /** Set when bought in boxes: which box and how many (quantity and purchase price are per piece). */
    @Column(name = "package_id")
    private Long packageId;

    @Column(precision = 12, scale = 3)
    private BigDecimal packageCount;

    public PurchaseItem() {
    }

    public PurchaseItem(Product product, BigDecimal quantity, BigDecimal purchasePrice, BigDecimal sellingPrice, BigDecimal taxRate, String unit, BigDecimal lineTotal) {
        this.product = product;
        this.quantity = quantity;
        this.purchasePrice = purchasePrice;
        this.sellingPrice = sellingPrice;
        this.taxRate = taxRate;
        this.unit = unit;
        this.lineTotal = lineTotal;
    }

    public Long getId() {
        return id;
    }

    public PurchaseInvoice getInvoice() {
        return invoice;
    }

    public void setInvoice(PurchaseInvoice invoice) {
        this.invoice = invoice;
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

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public BigDecimal getSellingPrice() {
        return sellingPrice;
    }

    public void setSellingPrice(BigDecimal sellingPrice) {
        this.sellingPrice = sellingPrice;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public Long getPackageId() {
        return packageId;
    }

    public BigDecimal getPackageCount() {
        return packageCount;
    }

    public void setPackage(Long packageId, BigDecimal packageCount) {
        this.packageId = packageId;
        this.packageCount = packageCount;
    }
}

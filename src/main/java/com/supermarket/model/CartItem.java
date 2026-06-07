package com.supermarket.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class CartItem {

    private Long productId;
    private String productName;
    private String barcode;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal taxRate;
    private String unit;

    public CartItem(Long productId, String productName, String barcode, Integer quantity, BigDecimal price, BigDecimal taxRate, String unit) {
        this.productId = productId;
        this.productName = productName;
        this.barcode = barcode;
        this.quantity = quantity;
        this.price = price;
        this.taxRate = taxRate;
        this.unit = unit;
    }

    public BigDecimal getLineTotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal getUnitPriceWithoutTax() {
        if (taxRate == null || taxRate.signum() == 0) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal divisor = BigDecimal.ONE.add(taxRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        return price.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    public BigDecimal getUnitTaxAmount() {
        return price.subtract(getUnitPriceWithoutTax()).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getTaxAmount() {
        return getUnitTaxAmount().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
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

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}

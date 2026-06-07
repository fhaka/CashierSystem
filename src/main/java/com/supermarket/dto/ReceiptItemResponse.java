package com.supermarket.dto;

import java.math.BigDecimal;

public class ReceiptItemResponse {

    private String productName;
    private String barcode;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal unitPriceWithoutTax;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal lineTotal;
    private String unit;

    public ReceiptItemResponse(String productName, String barcode, Integer quantity, BigDecimal unitPrice, BigDecimal unitPriceWithoutTax, BigDecimal taxRate, BigDecimal taxAmount, BigDecimal lineTotal, String unit) {
        this.productName = productName;
        this.barcode = barcode;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.unitPriceWithoutTax = unitPriceWithoutTax;
        this.taxRate = taxRate;
        this.taxAmount = taxAmount;
        this.lineTotal = lineTotal;
        this.unit = unit;
    }

    public String getProductName() {
        return productName;
    }

    public String getBarcode() {
        return barcode;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getUnitPriceWithoutTax() {
        return unitPriceWithoutTax;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public String getUnit() {
        return unit;
    }
}

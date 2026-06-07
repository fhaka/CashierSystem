package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ReceiptResponse {

    private Long saleId;
    private LocalDateTime date;
    private BigDecimal subtotal;
    private BigDecimal totalAmount;
    private List<ReceiptItemResponse> items;
    private String printableReceipt;

    public ReceiptResponse(Long saleId, LocalDateTime date, BigDecimal subtotal, BigDecimal totalAmount, List<ReceiptItemResponse> items, String printableReceipt) {
        this.saleId = saleId;
        this.date = date;
        this.subtotal = subtotal;
        this.totalAmount = totalAmount;
        this.items = items;
        this.printableReceipt = printableReceipt;
    }

    public Long getSaleId() {
        return saleId;
    }

    public LocalDateTime getDate() {
        return date;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<ReceiptItemResponse> getItems() {
        return items;
    }

    public String getPrintableReceipt() {
        return printableReceipt;
    }
}

package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Money and goods going back to a customer for (part of) an earlier sale. Paid out of the current shift. */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String refundNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    @JsonIgnore
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    @JsonIgnore
    private Shift shift;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private Cashier cashier;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "approved_by_id", nullable = false)
    private Cashier approvedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod method;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String reason;

    @OneToMany(mappedBy = "refund", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<RefundItem> items = new ArrayList<>();

    protected Refund() {
    }

    public Refund(Sale sale, Shift shift, Cashier cashier, Cashier approvedBy, PaymentMethod method, String reason, LocalDateTime createdAt) {
        this.sale = sale;
        this.shift = shift;
        this.cashier = cashier;
        this.approvedBy = approvedBy;
        this.method = method;
        this.reason = reason;
        this.createdAt = createdAt;
        this.totalAmount = BigDecimal.ZERO;
    }

    public void addItem(RefundItem item) {
        item.setRefund(this);
        items.add(item);
        totalAmount = totalAmount.add(item.getAmount());
    }

    public Long getId() {
        return id;
    }

    public String getRefundNumber() {
        return refundNumber;
    }

    public void setRefundNumber(String refundNumber) {
        this.refundNumber = refundNumber;
    }

    public Sale getSale() {
        return sale;
    }

    public String getInvoiceNumber() {
        return sale.getInvoiceNumber();
    }

    public Shift getShift() {
        return shift;
    }

    public Cashier getCashier() {
        return cashier;
    }

    public Cashier getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getReason() {
        return reason;
    }

    public List<RefundItem> getItems() {
        return items;
    }
}

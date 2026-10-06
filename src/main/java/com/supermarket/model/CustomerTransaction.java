package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One change of a customer's points or debt. A positive balance change means the customer owes more. */
@Entity
@Table(name = "customer_transactions")
public class CustomerTransaction {

    public enum Type { SALE, REFUND, PAYMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore
    private Customer customer;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    @JsonIgnore
    private Cashier cashier;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 20)
    private Type type;

    @Column(nullable = false)
    private int pointsChange;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceChange;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id")
    @JsonIgnore
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    @JsonIgnore
    private Refund refund;

    private String note;

    protected CustomerTransaction() {
    }

    public CustomerTransaction(Customer customer, Cashier cashier, Type type, int pointsChange, BigDecimal balanceChange,
                               Sale sale, Refund refund, String note, LocalDateTime createdAt) {
        this.customer = customer;
        this.cashier = cashier;
        this.type = type;
        this.pointsChange = pointsChange;
        this.balanceChange = balanceChange;
        this.sale = sale;
        this.refund = refund;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCashierName() {
        return cashier.getFullName();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Type getType() {
        return type;
    }

    public int getPointsChange() {
        return pointsChange;
    }

    public BigDecimal getBalanceChange() {
        return balanceChange;
    }

    public String getInvoiceNumber() {
        return sale == null ? null : sale.getInvoiceNumber();
    }

    public String getRefundNumber() {
        return refund == null ? null : refund.getRefundNumber();
    }

    public String getNote() {
        return note;
    }
}

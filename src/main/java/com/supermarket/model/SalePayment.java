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

/** One way the customer paid: for example 20 EUR in cash, or 500 LEK by card. */
@Entity
@Table(name = "sale_payments")
public class SalePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    @JsonIgnore
    private Sale sale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod method;

    @Column(nullable = false, length = 3)
    private String currency;

    /** In the payment's currency. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amountLek;

    protected SalePayment() {
    }

    public SalePayment(PaymentMethod method, String currency, BigDecimal amount, BigDecimal exchangeRate, BigDecimal amountLek) {
        this.method = method;
        this.currency = currency;
        this.amount = amount;
        this.exchangeRate = exchangeRate;
        this.amountLek = amountLek;
    }

    void setSale(Sale sale) {
        this.sale = sale;
    }

    public Long getId() {
        return id;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public BigDecimal getAmountLek() {
        return amountLek;
    }
}

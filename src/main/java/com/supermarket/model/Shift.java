package com.supermarket.model;

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
import java.time.LocalDateTime;

@Entity
@Table(name = "shifts")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "cashier_id", nullable = false)
    private Cashier cashier;

    @Column(nullable = false)
    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal openingCash;

    @Column(precision = 12, scale = 2)
    private BigDecimal closingCash;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalSales;

    @Column(precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(precision = 12, scale = 2)
    private BigDecimal difference;

    /** Breakdown behind expected cash, filled when the shift closes. Cash sales are net of change given. */
    @Column(precision = 12, scale = 2)
    private BigDecimal cashSales;

    @Column(precision = 12, scale = 2)
    private BigDecimal cardSales;

    @Column(precision = 12, scale = 2)
    private BigDecimal cashIn;

    @Column(precision = 12, scale = 2)
    private BigDecimal cashOut;

    @Column(precision = 12, scale = 2)
    private BigDecimal cashRefunds;

    @Column(precision = 12, scale = 2)
    private BigDecimal cardRefunds;

    @Column(nullable = false)
    private String status;

    public Shift() {
    }

    public Shift(Cashier cashier, LocalDateTime openedAt, BigDecimal openingCash) {
        this.cashier = cashier;
        this.openedAt = openedAt;
        this.openingCash = openingCash;
        this.status = "OPEN";
        this.totalSales = BigDecimal.ZERO;
        this.expectedCash = openingCash;
        this.difference = BigDecimal.ZERO;
    }

    public Long getId() {
        return id;
    }

    public Cashier getCashier() {
        return cashier;
    }

    public void setCashier(Cashier cashier) {
        this.cashier = cashier;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public BigDecimal getOpeningCash() {
        return openingCash;
    }

    public void setOpeningCash(BigDecimal openingCash) {
        this.openingCash = openingCash;
    }

    public BigDecimal getClosingCash() {
        return closingCash;
    }

    public void setClosingCash(BigDecimal closingCash) {
        this.closingCash = closingCash;
    }

    public BigDecimal getTotalSales() {
        return totalSales;
    }

    public void setTotalSales(BigDecimal totalSales) {
        this.totalSales = totalSales;
    }

    public BigDecimal getExpectedCash() {
        return expectedCash;
    }

    public void setExpectedCash(BigDecimal expectedCash) {
        this.expectedCash = expectedCash;
    }

    public BigDecimal getDifference() {
        return difference;
    }

    public void setDifference(BigDecimal difference) {
        this.difference = difference;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getCashSales() {
        return cashSales;
    }

    public void setCashSales(BigDecimal cashSales) {
        this.cashSales = cashSales;
    }

    public BigDecimal getCardSales() {
        return cardSales;
    }

    public void setCardSales(BigDecimal cardSales) {
        this.cardSales = cardSales;
    }

    public BigDecimal getCashIn() {
        return cashIn;
    }

    public void setCashIn(BigDecimal cashIn) {
        this.cashIn = cashIn;
    }

    public BigDecimal getCashOut() {
        return cashOut;
    }

    public void setCashOut(BigDecimal cashOut) {
        this.cashOut = cashOut;
    }

    public BigDecimal getCashRefunds() {
        return cashRefunds;
    }

    public void setCashRefunds(BigDecimal cashRefunds) {
        this.cashRefunds = cashRefunds;
    }

    public BigDecimal getCardRefunds() {
        return cardRefunds;
    }

    public void setCardRefunds(BigDecimal cardRefunds) {
        this.cardRefunds = cardRefunds;
    }
}

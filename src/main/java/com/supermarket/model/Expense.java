package com.supermarket.model;

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
import java.time.LocalDateTime;

/**
 * Money the shop spent outside of buying goods. Paid in CASH it leaves the till of the cashier's open shift (the
 * matching cash movement is kept); paid by BANK or CARD it is only recorded. A wrong expense is cancelled, never deleted.
 */
@Entity
@Table(name = "expenses")
public class Expense {

    public enum Method { CASH, BANK, CARD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ExpenseCategory category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate paidOn;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(length = 150)
    private String paidTo;

    private String note;

    @Column(name = "shift_id")
    private Long shiftId;

    @Column(name = "cash_movement_id")
    private Long cashMovementId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private Cashier cashier;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean cancelled;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "cancelled_by")
    private Cashier cancelledBy;

    private LocalDateTime cancelledAt;

    protected Expense() {
    }

    public Expense(ExpenseCategory category, BigDecimal amount, LocalDate paidOn, Method method, String paidTo, String note,
                   Cashier cashier, LocalDateTime createdAt) {
        this.category = category;
        this.amount = amount;
        this.paidOn = paidOn;
        this.method = method.name();
        this.paidTo = paidTo;
        this.note = note;
        this.cashier = cashier;
        this.createdAt = createdAt;
    }

    public void paidFromTill(Long shiftId, Long cashMovementId) {
        this.shiftId = shiftId;
        this.cashMovementId = cashMovementId;
    }

    public void cancel(Cashier by, LocalDateTime at) {
        this.cancelled = true;
        this.cancelledBy = by;
        this.cancelledAt = at;
    }

    public Long getId() {
        return id;
    }

    public ExpenseCategory getCategory() {
        return category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public String getMethod() {
        return method;
    }

    public String getPaidTo() {
        return paidTo;
    }

    public String getNote() {
        return note;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public Long getCashMovementId() {
        return cashMovementId;
    }

    public Cashier getCashier() {
        return cashier;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public Cashier getCancelledBy() {
        return cancelledBy;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }
}

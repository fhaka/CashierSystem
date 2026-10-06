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

/** Cash put into (IN) or taken out of (OUT) the drawer during a shift, outside of sales. */
@Entity
@Table(name = "cash_movements")
public class CashMovement {

    public enum Type { IN, OUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    @JsonIgnore
    private Shift shift;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private Cashier cashier;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 3)
    private Type type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected CashMovement() {
    }

    public CashMovement(Shift shift, Cashier cashier, Type type, BigDecimal amount, String reason, LocalDateTime createdAt) {
        this.shift = shift;
        this.cashier = cashier;
        this.type = type;
        this.amount = amount;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Shift getShift() {
        return shift;
    }

    public Cashier getCashier() {
        return cashier;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /** Positive for money in, negative for money out. */
    public BigDecimal getSignedAmount() {
        return type == Type.IN ? amount : amount.negate();
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

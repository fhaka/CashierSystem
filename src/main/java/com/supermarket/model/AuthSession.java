package com.supermarket.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** A signed-in till. The token itself is never stored, only its SHA-256 hash. */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @Column(length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private Cashier cashier;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    protected AuthSession() {
    }

    public AuthSession(String tokenHash, Cashier cashier, LocalDateTime now) {
        this.tokenHash = tokenHash;
        this.cashier = cashier;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Cashier getCashier() {
        return cashier;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}

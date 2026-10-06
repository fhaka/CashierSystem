package com.supermarket.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** LEK per unit of a foreign currency. Buy: customer pays in that currency. Sell: prices shown in it. */
@Entity
@Table(name = "exchange_rates")
public class ExchangeRate {

    @Id
    @Column(length = 3)
    private String currency;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal buyRate;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal sellRate;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected ExchangeRate() {
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBuyRate() {
        return buyRate;
    }

    public BigDecimal getSellRate() {
        return sellRate;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void update(BigDecimal buyRate, BigDecimal sellRate, LocalDateTime now) {
        this.buyRate = buyRate;
        this.sellRate = sellRate;
        this.updatedAt = now;
    }
}

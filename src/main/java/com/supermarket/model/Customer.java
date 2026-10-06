package com.supermarket.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A known customer: loyalty card, points, and an account for buying on credit ("në borxh").
 * balance is what the customer owes the shop; buying on credit needs a credit limit.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String cardNumber;

    @Column(nullable = false, length = 150)
    private String fullName;

    @Column(length = 50)
    private String phone;

    @Column(length = 150)
    private String email;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private int points;

    @Column(precision = 12, scale = 2)
    private BigDecimal creditLimit;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Customer() {
    }

    public Customer(String cardNumber, String fullName, LocalDateTime createdAt) {
        this.cardNumber = cardNumber;
        this.fullName = fullName;
        this.createdAt = createdAt;
    }

    /** How much more can be bought on credit, or null if the customer cannot buy on credit. */
    public BigDecimal getCreditAvailable() {
        return creditLimit == null ? null : creditLimit.subtract(balance).max(BigDecimal.ZERO);
    }

    public void changePoints(int change) {
        points = Math.max(0, points + change);
    }

    public void changeBalance(BigDecimal change) {
        balance = balance.add(change);
    }

    public Long getId() {
        return id;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public int getPoints() {
        return points;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

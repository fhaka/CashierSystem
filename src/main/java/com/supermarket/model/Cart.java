package com.supermarket.model;

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
import java.util.Optional;

/**
 * A customer's basket. Each cashier has at most one OPEN cart, the one on screen. A cart can be PARKED
 * (customer went to fetch something) and resumed later on any till.
 */
@Entity
@Table(name = "carts")
public class Cart {

    public enum Status { OPEN, PARKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private Cashier cashier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @Column(length = 100)
    private String label;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /** Tab 1, 2 or 3 of the cashier's till while the cart is open; none while parked. */
    private Integer slot;

    /** Discount on the whole cart, given by hand (a cashier needs a manager's approval above a limit). */
    @Column(precision = 5, scale = 2)
    private BigDecimal manualDiscountPercent;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(Cashier cashier, LocalDateTime now) {
        this.cashier = cashier;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The line of single pieces (packageId null) or of a box of a product. */
    public Optional<CartItem> findItem(Long productId, Long packageId) {
        return items.stream().filter(item -> item.isSameLine(productId, packageId)).findFirst();
    }

    public Optional<CartItem> findLine(Long lineId) {
        return items.stream().filter(item -> lineId.equals(item.getLineId())).findFirst();
    }

    /** Pieces of a product in the whole cart, single pieces and boxes together. */
    public BigDecimal piecesOf(Long productId) {
        return items.stream().filter(item -> item.getProductId().equals(productId))
                .map(CartItem::getStockQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void addItem(CartItem item) {
        item.setCart(this);
        items.add(item);
    }

    public void removeItem(CartItem item) {
        items.remove(item);
    }

    public void clearItems() {
        items.clear();
    }

    public BigDecimal getSubtotal() {
        return items.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public BigDecimal getManualDiscountPercent() {
        return manualDiscountPercent;
    }

    public void setManualDiscountPercent(BigDecimal manualDiscountPercent) {
        this.manualDiscountPercent = manualDiscountPercent;
    }

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }
}

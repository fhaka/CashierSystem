package com.supermarket.model;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;

/**
 * A promotion the till applies by itself. PERCENT takes a percentage off; BUY_X_GET_Y gives freeQuantity
 * pieces free for every buyQuantity + freeQuantity bought. It covers one product or a whole category and can
 * be limited to dates, days of the week (1 = Monday ... 7 = Sunday) and hours (happy hour).
 */
@Entity
@Table(name = "promotions")
public class Promotion {

    public enum Type { PERCENT, BUY_X_GET_Y }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "promo_type", nullable = false, length = 20)
    private Type type;

    @Column(precision = 5, scale = 2)
    private BigDecimal discountPercent;

    private Integer buyQuantity;

    private Integer freeQuantity;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    private Category category;

    private LocalDate startsOn;

    private LocalDate endsOn;

    @Column(length = 20)
    private String daysOfWeek;

    private LocalTime startTime;

    private LocalTime endTime;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Promotion() {
    }

    public Promotion(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** Whether the promotion runs at this moment (dates, day of week and hours). */
    public boolean runsAt(LocalDateTime moment) {
        LocalDate day = moment.toLocalDate();
        if (!active || (startsOn != null && day.isBefore(startsOn)) || (endsOn != null && day.isAfter(endsOn))) {
            return false;
        }
        if (daysOfWeek != null && !daysOfWeek.isBlank() && Arrays.stream(daysOfWeek.split(","))
                .map(String::trim).noneMatch(d -> d.equals(String.valueOf(day.getDayOfWeek().getValue())))) {
            return false;
        }
        LocalTime time = moment.toLocalTime();
        return (startTime == null || !time.isBefore(startTime)) && (endTime == null || time.isBefore(endTime));
    }

    public boolean covers(Long productId, Long categoryId) {
        return (product != null && product.getId().equals(productId))
                || (category != null && categoryId != null && category.getId().equals(categoryId));
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent = discountPercent;
    }

    public Integer getBuyQuantity() {
        return buyQuantity;
    }

    public void setBuyQuantity(Integer buyQuantity) {
        this.buyQuantity = buyQuantity;
    }

    public Integer getFreeQuantity() {
        return freeQuantity;
    }

    public void setFreeQuantity(Integer freeQuantity) {
        this.freeQuantity = freeQuantity;
    }

    public Long getProductId() {
        return product == null ? null : product.getId();
    }

    public String getProductName() {
        return product == null ? null : product.getName();
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Long getCategoryId() {
        return category == null ? null : category.getId();
    }

    public String getCategoryName() {
        return category == null ? null : category.getName();
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public void setStartsOn(LocalDate startsOn) {
        this.startsOn = startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setEndsOn(LocalDate endsOn) {
        this.endsOn = endsOn;
    }

    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(String daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
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

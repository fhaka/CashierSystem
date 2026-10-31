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

/**
 * Goods given back to a supplier. CREDIT returns lower what the shop owes the supplier (DONE at once); REPLACEMENT
 * returns wait (PENDING) until the same goods come back (REPLACED). The goods leave stock when the return is made.
 */
@Entity
@Table(name = "supplier_returns")
public class SupplierReturn {

    public enum Kind { CREDIT, REPLACEMENT }

    public enum Status { DONE, PENDING, REPLACED }

    public enum Reason { DAMAGED, EXPIRED, WRONG, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String number;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    private String note;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private Cashier createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "replaced_by")
    private Cashier replacedBy;

    private LocalDateTime replacedAt;

    @OneToMany(mappedBy = "supplierReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SupplierReturnLine> lines = new ArrayList<>();

    protected SupplierReturn() {
    }

    public SupplierReturn(String number, Supplier supplier, Kind kind, Reason reason, String note, Cashier createdBy, LocalDateTime createdAt) {
        this.number = number;
        this.supplier = supplier;
        this.kind = kind;
        this.status = kind == Kind.CREDIT ? Status.DONE : Status.PENDING;
        this.reason = reason;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void addLine(SupplierReturnLine line) {
        line.setSupplierReturn(this);
        lines.add(line);
        totalAmount = totalAmount.add(line.getLineTotal());
    }

    public void markReplaced(Cashier by, LocalDateTime at) {
        status = Status.REPLACED;
        replacedBy = by;
        replacedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public Kind getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public Reason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Cashier getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Cashier getReplacedBy() {
        return replacedBy;
    }

    public LocalDateTime getReplacedAt() {
        return replacedAt;
    }

    public List<SupplierReturnLine> getLines() {
        return lines;
    }
}

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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An order to a supplier. OPEN while it is being prepared, SENT once sent (it can still be corrected), then RECEIVED
 * when the goods came (with the supplier's purchase invoice) or CANCELLED. It never moves stock.
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    public enum Status { OPEN, SENT, RECEIVED, CANCELLED }

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
    private Status status = Status.OPEN;

    private String note;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private Cashier createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "closed_by")
    private Cashier closedBy;

    private LocalDateTime closedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(String number, Supplier supplier, String note, Cashier createdBy, LocalDateTime createdAt) {
        this.number = number;
        this.supplier = supplier;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public boolean isEditable() {
        return status == Status.OPEN || status == Status.SENT;
    }

    public void replaceLines(List<PurchaseOrderLine> newLines) {
        lines.clear();
        for (PurchaseOrderLine line : newLines) {
            line.setOrder(this);
            lines.add(line);
        }
    }

    public void markSent(LocalDateTime at) {
        status = Status.SENT;
        sentAt = at;
    }

    public void close(Status outcome, Cashier by, LocalDateTime at) {
        status = outcome;
        closedBy = by;
        closedAt = at;
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

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public Status getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Cashier getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public Cashier getClosedBy() {
        return closedBy;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public List<PurchaseOrderLine> getLines() {
        return lines;
    }
}

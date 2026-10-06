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

import java.time.LocalDateTime;

/** One line of the audit log: who did what, on what, when, and which manager approved it. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "cashier_id")
    private Cashier cashier;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "approved_by_id")
    private Cashier approvedBy;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(length = 40)
    private String entityType;

    @Column(length = 40)
    private String entityId;

    @Column(length = 1000)
    private String details;

    protected AuditEvent() {
    }

    public AuditEvent(Cashier cashier, Cashier approvedBy, String action, String entityType, String entityId,
                      String details, LocalDateTime createdAt) {
        this.cashier = cashier;
        this.approvedBy = approvedBy;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getCashierName() {
        return cashier == null ? null : cashier.getFullName();
    }

    public String getApprovedByName() {
        return approvedBy == null ? null : approvedBy.getFullName();
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getDetails() {
        return details;
    }
}

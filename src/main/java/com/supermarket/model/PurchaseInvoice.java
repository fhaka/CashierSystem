package com.supermarket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase_invoices")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class PurchaseInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String invoiceNumber;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private LocalDate invoiceDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** When the invoice was entered (null for invoices entered before 1.4 and not found in the audit log). */
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseItem> items = new ArrayList<>();

    public PurchaseInvoice() {
    }

    public PurchaseInvoice(String invoiceNumber, String company, LocalDate invoiceDate, BigDecimal totalAmount) {
        this.invoiceNumber = invoiceNumber;
        this.company = company;
        this.invoiceDate = invoiceDate;
        this.totalAmount = totalAmount;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * When the goods of an invoice count as arrived: the moment it was entered if that was on the invoice date (so it
     * sorts right among the sales of that day), otherwise the start of the invoice date.
     */
    public static LocalDateTime movementTime(LocalDate invoiceDate, LocalDateTime createdAt) {
        return createdAt != null && createdAt.toLocalDate().equals(invoiceDate) ? createdAt : invoiceDate.atStartOfDay();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void addItem(PurchaseItem item) {
        items.add(item);
        item.setInvoice(this);
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public List<PurchaseItem> getItems() {
        return items;
    }

    public void setItems(List<PurchaseItem> items) {
        this.items = items;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }
}

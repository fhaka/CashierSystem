package com.supermarket.dto;

import com.supermarket.model.PurchaseInvoice;
import com.supermarket.model.Supplier;
import com.supermarket.model.SupplierPayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Requests and answers of the supplier screens. */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record SupplierRequest(String name, String taxNumber, String phone, String email, String address,
                                  String notes, Boolean active) {
    }

    public record PaymentRequest(BigDecimal amount, LocalDate paidOn, String method, String note) {
    }

    /** A supplier with what was bought from them, what was paid, and what is still owed. */
    public record SupplierSummary(Long id, String name, String taxNumber, String phone, String email, String address,
                                  String notes, boolean active, BigDecimal totalInvoiced, BigDecimal totalPaid,
                                  BigDecimal balance) {

        public static SupplierSummary of(Supplier s, BigDecimal invoiced, BigDecimal paid) {
            return new SupplierSummary(s.getId(), s.getName(), s.getTaxNumber(), s.getPhone(), s.getEmail(), s.getAddress(),
                    s.getNotes(), s.isActive(), invoiced, paid, invoiced.subtract(paid));
        }
    }

    public record InvoiceLine(Long id, String invoiceNumber, LocalDate invoiceDate, int lines, BigDecimal totalAmount) {

        public static InvoiceLine of(PurchaseInvoice invoice) {
            return new InvoiceLine(invoice.getId(), invoice.getInvoiceNumber(), invoice.getInvoiceDate(),
                    invoice.getItems().size(), invoice.getTotalAmount());
        }
    }

    public record SupplierDetail(SupplierSummary supplier, List<InvoiceLine> invoices, List<SupplierPayment> payments) {
    }
}

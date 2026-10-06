package com.supermarket.service;

import com.supermarket.dto.SupplierDtos.InvoiceLine;
import com.supermarket.dto.SupplierDtos.PaymentRequest;
import com.supermarket.dto.SupplierDtos.SupplierDetail;
import com.supermarket.dto.SupplierDtos.SupplierRequest;
import com.supermarket.dto.SupplierDtos.SupplierSummary;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.Supplier;
import com.supermarket.model.SupplierPayment;
import com.supermarket.repository.PurchaseInvoiceRepository;
import com.supermarket.repository.SupplierPaymentRepository;
import com.supermarket.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Suppliers, their purchase invoices and payments. What is owed = invoices - payments. */
@Service
public class SupplierService {

    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "BANK");

    private final SupplierRepository supplierRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final PurchaseInvoiceRepository invoiceRepository;
    private final AuditService auditService;

    public SupplierService(SupplierRepository supplierRepository, SupplierPaymentRepository paymentRepository,
                           PurchaseInvoiceRepository invoiceRepository, AuditService auditService) {
        this.supplierRepository = supplierRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SupplierSummary> findAll() {
        return supplierRepository.findAllByOrderByNameAsc().stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public SupplierDetail detail(Long id) {
        Supplier supplier = find(id);
        return new SupplierDetail(summary(supplier),
                invoiceRepository.findForSupplier(id).stream().map(InvoiceLine::of).toList(),
                paymentRepository.findForSupplier(id));
    }

    @Transactional
    public SupplierSummary create(SupplierRequest request, Cashier actor) {
        String name = requireName(request);
        requireFreeName(name, null);
        Supplier supplier = new Supplier(name, LocalDateTime.now());
        apply(supplier, request);
        Supplier saved = supplierRepository.save(supplier);
        auditService.record(actor, "SUPPLIER_CREATED", "SUPPLIER", saved.getId(), saved.getName());
        return summary(saved);
    }

    @Transactional
    public SupplierSummary update(Long id, SupplierRequest request, Cashier actor) {
        Supplier supplier = find(id);
        String name = requireName(request);
        requireFreeName(name, id);
        String before = supplier.getName();
        supplier.setName(name);
        apply(supplier, request);
        auditService.record(actor, "SUPPLIER_UPDATED", "SUPPLIER", id, before.equals(name) ? name : before + " -> " + name);
        return summary(supplier);
    }

    @Transactional
    public SupplierPayment addPayment(Long id, PaymentRequest request, Cashier actor) {
        Supplier supplier = find(id);
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("payment.amountPositive");
        }
        String method = request.method() == null ? "BANK" : request.method().trim().toUpperCase();
        if (!PAYMENT_METHODS.contains(method)) {
            throw new ValidationException("supplier.invalidPaymentMethod");
        }
        SupplierPayment payment = paymentRepository.save(new SupplierPayment(supplier, actor,
                request.amount().setScale(2, RoundingMode.HALF_UP), request.paidOn() == null ? LocalDate.now() : request.paidOn(),
                method, blankToNull(request.note()), LocalDateTime.now()));
        auditService.record(actor, "SUPPLIER_PAYMENT", "SUPPLIER", id, supplier.getName() + ": " + payment.getAmount() + " LEK " + method);
        return payment;
    }

    /** The supplier of a new purchase invoice: chosen from the list, or created on the fly from a typed name. */
    @Transactional
    public Supplier resolveForPurchase(Long supplierId, String typedName, Cashier actor) {
        if (supplierId != null) {
            return find(supplierId);
        }
        if (typedName == null || typedName.isBlank()) {
            throw new ValidationException("purchase.companyRequired");
        }
        String name = typedName.trim();
        return supplierRepository.findByNameIgnoreCase(name).orElseGet(() -> {
            Supplier created = supplierRepository.save(new Supplier(name, LocalDateTime.now()));
            auditService.record(actor, "SUPPLIER_CREATED", "SUPPLIER", created.getId(), name + " (from purchase invoice)");
            return created;
        });
    }

    private SupplierSummary summary(Supplier supplier) {
        return SupplierSummary.of(supplier, invoiceRepository.sumForSupplier(supplier.getId()),
                paymentRepository.sumForSupplier(supplier.getId()));
    }

    private Supplier find(Long id) {
        return supplierRepository.findById(id).orElseThrow(() -> new ValidationException("supplier.notFound", id));
    }

    private void requireFreeName(String name, Long ownId) {
        supplierRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(ownId))
                .ifPresent(other -> {
                    throw new ValidationException("supplier.nameTaken");
                });
    }

    private static String requireName(SupplierRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ValidationException("supplier.nameRequired");
        }
        return request.name().trim();
    }

    private static void apply(Supplier supplier, SupplierRequest request) {
        supplier.setTaxNumber(blankToNull(request.taxNumber()));
        supplier.setPhone(blankToNull(request.phone()));
        supplier.setEmail(blankToNull(request.email()));
        supplier.setAddress(blankToNull(request.address()));
        supplier.setNotes(blankToNull(request.notes()));
        supplier.setActive(request.active() == null || request.active());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

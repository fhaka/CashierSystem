package com.supermarket.repository;

import com.supermarket.model.PurchaseInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, Long> {
    boolean existsByInvoiceNumberIgnoreCase(String invoiceNumber);
}

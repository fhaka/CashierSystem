package com.supermarket.repository;

import com.supermarket.model.PurchaseInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, Long> {
    boolean existsByInvoiceNumberIgnoreCase(String invoiceNumber);

    @Query("select v from PurchaseInvoice v where v.supplier.id = :supplierId order by v.invoiceDate desc, v.id desc")
    List<PurchaseInvoice> findForSupplier(@Param("supplierId") Long supplierId);

    @Query("select coalesce(sum(v.totalAmount), 0) from PurchaseInvoice v where v.supplier.id = :supplierId")
    BigDecimal sumForSupplier(@Param("supplierId") Long supplierId);
}

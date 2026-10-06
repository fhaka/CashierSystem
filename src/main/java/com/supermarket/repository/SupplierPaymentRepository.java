package com.supermarket.repository;

import com.supermarket.model.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    @Query("select p from SupplierPayment p where p.supplier.id = :supplierId order by p.paidOn desc, p.id desc")
    List<SupplierPayment> findForSupplier(@Param("supplierId") Long supplierId);

    @Query("select coalesce(sum(p.amount), 0) from SupplierPayment p where p.supplier.id = :supplierId")
    BigDecimal sumForSupplier(@Param("supplierId") Long supplierId);
}

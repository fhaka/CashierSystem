package com.supermarket.repository;

import com.supermarket.model.SupplierReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, Long> {

    @Query("select r from SupplierReturn r order by r.createdAt desc, r.id desc")
    List<SupplierReturn> findAllNewestFirst();

    @Query("select r from SupplierReturn r where r.status = com.supermarket.model.SupplierReturn.Status.PENDING order by r.createdAt desc")
    List<SupplierReturn> findPending();

    @Query("select r from SupplierReturn r where r.supplier.id = :supplierId order by r.createdAt desc, r.id desc")
    List<SupplierReturn> findForSupplier(@Param("supplierId") Long supplierId);

    /** What the supplier took off the shop's debt: credit returns. */
    @Query("""
            select coalesce(sum(r.totalAmount), 0) from SupplierReturn r
            where r.supplier.id = :supplierId and r.kind = com.supermarket.model.SupplierReturn.Kind.CREDIT""")
    BigDecimal sumCreditForSupplier(@Param("supplierId") Long supplierId);
}

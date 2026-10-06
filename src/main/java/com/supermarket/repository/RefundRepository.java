package com.supermarket.repository;

import com.supermarket.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    @Query("select r from Refund r where r.sale.id = :saleId order by r.id")
    List<Refund> findForSale(@Param("saleId") Long saleId);

    @Query("select r from Refund r where r.shift.id = :shiftId order by r.id")
    List<Refund> findForShift(@Param("shiftId") Long shiftId);

    List<Refund> findByCreatedAtBetweenOrderByCreatedAt(LocalDateTime from, LocalDateTime to);
}

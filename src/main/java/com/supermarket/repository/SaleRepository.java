package com.supermarket.repository;

import com.supermarket.model.Sale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    List<Sale> findAllByOrderByDateDesc();

    List<Sale> findByCashierIdOrderByDateDesc(Long cashierId);

    @Query("""
            select coalesce(sum(s.totalAmount), 0)
            from Sale s
            where s.cashier.id = :cashierId
              and s.date >= :from
              and s.date <= :to
            """)
    BigDecimal sumTotalByCashierAndDateBetween(
            @Param("cashierId") Long cashierId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query("select s from Sale s where s.shift.id = :shiftId order by s.date")
    List<Sale> findByShiftIdOrderByDate(@Param("shiftId") Long shiftId);

    List<Sale> findByDateBetweenOrderByDate(LocalDateTime from, LocalDateTime to);
}

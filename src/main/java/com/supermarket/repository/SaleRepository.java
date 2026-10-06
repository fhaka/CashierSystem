package com.supermarket.repository;

import com.supermarket.model.Sale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface SaleRepository extends JpaRepository<Sale, Long> {

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

    java.util.Optional<Sale> findByInvoiceNumber(String invoiceNumber);

    /** The sales log: newest first, optionally only one cashier, a cashier name or an invoice number. */
    @Query(value = """
            select s from Sale s left join s.cashier c
            where s.date >= :from and s.date <= :to
              and (:cashierId is null or c.id = :cashierId)
              and (:cashierName is null or lower(c.fullName) like lower(concat('%', :cashierName, '%')))
              and (:invoice is null or s.invoiceNumber like concat('%', :invoice, '%'))
            order by s.date desc, s.id desc
            """, countQuery = """
            select count(s) from Sale s left join s.cashier c
            where s.date >= :from and s.date <= :to
              and (:cashierId is null or c.id = :cashierId)
              and (:cashierName is null or lower(c.fullName) like lower(concat('%', :cashierName, '%')))
              and (:invoice is null or s.invoiceNumber like concat('%', :invoice, '%'))
            """)
    Page<Sale> search(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("cashierId") Long cashierId,
                      @Param("cashierName") String cashierName, @Param("invoice") String invoice, Pageable pageable);

    @Query("""
            select coalesce(sum(s.totalAmount), 0) from Sale s left join s.cashier c
            where s.date >= :from and s.date <= :to
              and (:cashierId is null or c.id = :cashierId)
              and (:cashierName is null or lower(c.fullName) like lower(concat('%', :cashierName, '%')))
              and (:invoice is null or s.invoiceNumber like concat('%', :invoice, '%'))
            """)
    BigDecimal sumForSearch(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("cashierId") Long cashierId,
                            @Param("cashierName") String cashierName, @Param("invoice") String invoice);

    @Query("select count(s), coalesce(sum(s.totalAmount), 0), max(s.date) from Sale s where s.date >= :from and (:cashierId is null or s.cashier.id = :cashierId)")
    List<Object[]> todaySummary(@Param("from") LocalDateTime from, @Param("cashierId") Long cashierId);
}

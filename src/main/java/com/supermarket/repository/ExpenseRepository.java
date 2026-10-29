package com.supermarket.repository;

import com.supermarket.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @Query("select e from Expense e where e.paidOn >= :from and e.paidOn <= :to order by e.paidOn desc, e.id desc")
    List<Expense> findPaidBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select e from Expense e where e.shiftId = :shiftId and e.cancelled = false order by e.id")
    List<Expense> findForShift(@Param("shiftId") Long shiftId);

    @Query("select e.category.id, count(e) from Expense e group by e.category.id")
    List<Object[]> countByCategory();
}

package com.supermarket.repository;

import com.supermarket.model.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    @Query("select a from StockAdjustment a where a.product.id = :productId order by a.createdAt desc, a.id desc")
    List<StockAdjustment> findForProduct(@Param("productId") Long productId);
}

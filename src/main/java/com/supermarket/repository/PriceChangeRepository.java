package com.supermarket.repository;

import com.supermarket.model.PriceChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PriceChangeRepository extends JpaRepository<PriceChange, Long> {

    @Query("select c from PriceChange c left join fetch c.cashier where c.productId = :productId order by c.changedAt desc, c.id desc")
    List<PriceChange> findForProduct(@Param("productId") Long productId);

    /** Products (and boxes) whose selling price changed since a moment: their shelf labels are out of date. */
    @Query("select distinct c.productId from PriceChange c where c.changedAt >= :since and c.newPrice is not null and (c.oldPrice is null or c.oldPrice <> c.newPrice)")
    List<Long> productsWithNewPriceSince(@Param("since") LocalDateTime since);
}

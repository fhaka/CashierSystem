package com.supermarket.repository;

import com.supermarket.model.PurchaseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Long> {

    /** Deliveries of these products, newest first: the first one per product names its last supplier. */
    @Query("""
            select i from PurchaseItem i join fetch i.invoice v
            where i.product.id in :productIds
            order by v.invoiceDate desc, i.id desc
            """)
    List<PurchaseItem> findLatestForProducts(@Param("productIds") Collection<Long> productIds);

    /** Deliveries with a best-before date, for active products that still have stock. */
    @Query("""
            select i from PurchaseItem i join fetch i.product p join fetch i.invoice v
            where i.expiryDate is not null and p.active = true and p.stock > 0
            order by p.id, v.invoiceDate desc, i.id desc
            """)
    List<PurchaseItem> findWithExpiryForProductsInStock();
}

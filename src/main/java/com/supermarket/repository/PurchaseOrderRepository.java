package com.supermarket.repository;

import com.supermarket.model.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("select o from PurchaseOrder o where o.status in :statuses order by o.createdAt desc, o.id desc")
    List<PurchaseOrder> findByStatuses(@Param("statuses") Collection<PurchaseOrder.Status> statuses);

    @Query("select o from PurchaseOrder o order by o.createdAt desc, o.id desc")
    List<PurchaseOrder> findAllNewestFirst();

    /** Product id, package id, quantity of every line on an order still waiting for its goods. */
    @Query("""
            select l.product.id, l.packageId, l.quantity from PurchaseOrderLine l join l.order o
            where o.status in (com.supermarket.model.PurchaseOrder.Status.OPEN, com.supermarket.model.PurchaseOrder.Status.SENT)""")
    List<Object[]> openLines();
}

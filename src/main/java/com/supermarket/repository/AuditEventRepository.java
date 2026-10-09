package com.supermarket.repository;

import com.supermarket.model.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Newest first. action and cashierId are optional filters (null = all). */
    @Query("""
            select e from AuditEvent e
            where e.createdAt >= :from and e.createdAt <= :to
              and (:action is null or e.action = :action)
              and (:cashierId is null or e.cashier.id = :cashierId)
            order by e.createdAt desc, e.id desc
            """)
    List<AuditEvent> search(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                            @Param("action") String action, @Param("cashierId") Long cashierId, Pageable page);

    /** Newest first: everything recorded about one product, user, ... */
    @Query("select e from AuditEvent e where e.entityType = :type and e.entityId = :id order by e.createdAt desc, e.id desc")
    List<AuditEvent> findForEntity(@Param("type") String type, @Param("id") String id, Pageable page);
}

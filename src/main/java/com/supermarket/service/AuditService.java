package com.supermarket.service;

import com.supermarket.model.AuditEvent;
import com.supermarket.model.Cashier;
import com.supermarket.repository.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The audit log: who did what and when, and which manager approved it. Entries are only ever added.
 * {@link #record} joins the caller's transaction, so an action that fails leaves no entry;
 * {@link #recordAlways} is for refused attempts (wrong PIN, failed login) that must be kept anyway.
 */
@Service
public class AuditService {

    private static final int MAX_DETAILS = 1000;

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public void record(Cashier actor, Cashier approver, String action, String entityType, Object entityId, String details) {
        auditEventRepository.save(event(actor, approver, action, entityType, entityId, details));
    }

    public void record(Cashier actor, String action, String entityType, Object entityId, String details) {
        record(actor, null, action, entityType, entityId, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAlways(Cashier actor, String action, String entityType, Object entityId, String details) {
        auditEventRepository.save(event(actor, null, action, entityType, entityId, details));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> search(LocalDateTime from, LocalDateTime to, String action, Long cashierId, int limit) {
        String actionFilter = action == null || action.isBlank() ? null : action.trim().toUpperCase();
        return auditEventRepository.search(from, to, actionFilter, cashierId, PageRequest.of(0, Math.min(Math.max(limit, 1), 2000)));
    }

    private static AuditEvent event(Cashier actor, Cashier approver, String action, String entityType, Object entityId, String details) {
        String text = details == null || details.length() <= MAX_DETAILS ? details : details.substring(0, MAX_DETAILS);
        // Self-approval by a manager is not an approval worth showing.
        Cashier approvedBy = approver != null && actor != null && approver.getId().equals(actor.getId()) ? null : approver;
        return new AuditEvent(actor, approvedBy, action, entityType, entityId == null ? null : String.valueOf(entityId),
                text, LocalDateTime.now());
    }
}

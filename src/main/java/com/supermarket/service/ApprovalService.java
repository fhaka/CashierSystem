package com.supermarket.service;

import com.supermarket.exception.ApprovalRequiredException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.repository.CashierRepository;
import com.supermarket.util.PasswordUtil;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Manager approval at the till. Sensitive actions by a cashier (voids, refunds, price changes) need the
 * personal PIN of a Super Cashier or Super Admin; managers approve their own actions. Too many wrong PINs
 * from one cashier pause approvals for a few minutes, so a 4-digit PIN cannot be guessed by trying.
 */
@Service
public class ApprovalService {

    private static final Pattern PIN_FORMAT = Pattern.compile("\\d{4,8}");
    private static final int MAX_WRONG_PINS = 5;
    private static final Duration PAUSE = Duration.ofMinutes(5);

    private final CashierRepository cashierRepository;
    private final AuditService auditService;
    private final ConcurrentMap<Long, WrongPins> wrongPins = new ConcurrentHashMap<>();

    public ApprovalService(CashierRepository cashierRepository, AuditService auditService) {
        this.cashierRepository = cashierRepository;
        this.auditService = auditService;
    }

    /** Returns the manager who approves: the requester if they are a manager, otherwise the owner of the PIN. */
    public Cashier approve(Cashier requester, String pin, String action) {
        if (requester.getRole().isOperationalManager()) {
            return requester;
        }
        LocalDateTime now = LocalDateTime.now();
        WrongPins wrong = wrongPins.get(requester.getId());
        if (wrong != null && wrong.pausedUntil != null && wrong.pausedUntil.isAfter(now)) {
            throw new ApprovalRequiredException("approval.paused",
                    Math.max(1, Duration.between(now, wrong.pausedUntil).toMinutes() + 1));
        }
        if (pin == null || pin.isBlank()) {
            throw new ApprovalRequiredException("approval.required");
        }
        for (Cashier manager : managersWithPin()) {
            if (PasswordUtil.matches(pin.trim(), manager.getApprovalPinHash())) {
                wrongPins.remove(requester.getId());
                return manager;
            }
        }
        registerWrongPin(requester, now);
        auditService.recordAlways(requester, "APPROVAL_FAILED", null, null, "action=" + action);
        throw new ApprovalRequiredException("approval.invalidPin");
    }

    /** Validates a new PIN for a manager. It must not be the same as another manager's PIN. */
    public String hashNewPin(Cashier manager, String pin) {
        if (pin == null || !PIN_FORMAT.matcher(pin.trim()).matches()) {
            throw new ValidationException("approval.pinFormat");
        }
        for (Cashier other : managersWithPin()) {
            if (!other.getId().equals(manager.getId()) && PasswordUtil.matches(pin.trim(), other.getApprovalPinHash())) {
                throw new ValidationException("approval.pinTaken");
            }
        }
        return PasswordUtil.hash(pin.trim());
    }

    private List<Cashier> managersWithPin() {
        return cashierRepository.findAll().stream()
                .filter(Cashier::isActive)
                .filter(cashier -> cashier.getRole().isOperationalManager())
                .filter(cashier -> cashier.getApprovalPinHash() != null)
                .toList();
    }

    private void registerWrongPin(Cashier requester, LocalDateTime now) {
        wrongPins.compute(requester.getId(), (id, current) -> {
            WrongPins next = current == null || (current.pausedUntil != null && !current.pausedUntil.isAfter(now))
                    ? new WrongPins() : current;
            next.count++;
            if (next.count >= MAX_WRONG_PINS) {
                next.count = 0;
                next.pausedUntil = now.plus(PAUSE);
            }
            return next;
        });
    }

    private static final class WrongPins {
        private int count;
        private LocalDateTime pausedUntil;
    }
}

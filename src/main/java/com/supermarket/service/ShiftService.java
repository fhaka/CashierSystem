package com.supermarket.service;

import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.exception.ValidationException;
import com.supermarket.dto.CashMovementRequest;
import com.supermarket.dto.ShiftCloseRequest;
import com.supermarket.dto.ShiftOpenRequest;
import com.supermarket.model.CashMovement;
import com.supermarket.model.Cashier;
import com.supermarket.model.Shift;
import com.supermarket.repository.CashMovementRepository;
import com.supermarket.repository.RefundRepository;
import com.supermarket.repository.SaleRepository;
import com.supermarket.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final SaleRepository saleRepository;
    private final CashMovementRepository cashMovementRepository;
    private final RefundRepository refundRepository;
    private final AuditService auditService;

    public ShiftService(ShiftRepository shiftRepository, SaleRepository saleRepository,
                        CashMovementRepository cashMovementRepository, RefundRepository refundRepository,
                        AuditService auditService) {
        this.shiftRepository = shiftRepository;
        this.saleRepository = saleRepository;
        this.cashMovementRepository = cashMovementRepository;
        this.refundRepository = refundRepository;
        this.auditService = auditService;
    }

    public List<Shift> findAll() {
        return shiftRepository.findAllByOrderByOpenedAtDesc();
    }

    public List<Shift> findForCashier(Long cashierId) {
        return shiftRepository.findByCashierIdOrderByOpenedAtDesc(cashierId);
    }

    public Optional<Shift> findOpenShift(Long cashierId) {
        return shiftRepository.findFirstByCashierIdAndStatusOrderByOpenedAtDesc(cashierId, "OPEN");
    }

    @Transactional
    public Shift openShift(ShiftOpenRequest request, Cashier cashier) {
        BigDecimal openingCash = request.getOpeningCash() == null ? BigDecimal.ZERO : request.getOpeningCash();
        if (openingCash.signum() < 0) {
            throw new ValidationException("shift.openingCashNegative");
        }
        findOpenShift(cashier.getId()).ifPresent(shift -> {
            throw new ValidationException("shift.alreadyOpen");
        });
        Shift opened = shiftRepository.save(new Shift(cashier, LocalDateTime.now(), openingCash));
        auditService.record(cashier, "SHIFT_OPENED", "SHIFT", opened.getId(), "opening=" + openingCash);
        return opened;
    }

    @Transactional
    public Shift closeShift(Long shiftId, ShiftCloseRequest request, Cashier cashier) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ValidationException("shift.notFound", shiftId));
        if (!shift.getCashier().getId().equals(cashier.getId())) {
            throw new PermissionDeniedException("shift.onlyOwnClose");
        }
        if (!"OPEN".equals(shift.getStatus())) {
            throw new ValidationException("shift.alreadyClosed");
        }
        BigDecimal closingCash = request.getClosingCash() == null ? BigDecimal.ZERO : request.getClosingCash();
        if (closingCash.signum() < 0) {
            throw new ValidationException("shift.closingCashNegative");
        }

        CashTotals totals = totals(shift);
        BigDecimal expectedCash = totals.expectedCash(shift.getOpeningCash());

        shift.setClosedAt(LocalDateTime.now());
        shift.setClosingCash(closingCash);
        shift.setTotalSales(totals.totalSales());
        shift.setCashSales(totals.cashSales());
        shift.setCardSales(totals.cardSales());
        shift.setCashIn(totals.cashIn());
        shift.setCashOut(totals.cashOut());
        shift.setCashRefunds(totals.cashRefunds());
        shift.setCardRefunds(totals.cardRefunds());
        shift.setCreditSales(totals.creditSales());
        shift.setExpectedCash(expectedCash);
        shift.setDifference(closingCash.subtract(expectedCash));
        shift.setStatus("CLOSED");
        Shift closed = shiftRepository.save(shift);
        auditService.record(cashier, "SHIFT_CLOSED", "SHIFT", closed.getId(),
                "expected=" + closed.getExpectedCash() + ", counted=" + closed.getClosingCash() + ", difference=" + closed.getDifference());
        return closed;
    }

    /** Money totals of the shift so far: its sales and its drawer movements. */
    @Transactional(readOnly = true)
    public CashTotals totals(Shift shift) {
        return CashTotals.of(
                saleRepository.findByShiftIdOrderByDate(shift.getId()),
                cashMovementRepository.findByShiftIdOrderByCreatedAt(shift.getId()),
                refundRepository.findForShift(shift.getId()));
    }

    public Shift findById(Long shiftId) {
        return shiftRepository.findById(shiftId).orElseThrow(() -> new ValidationException("shift.notFound", shiftId));
    }

    /** Cash put into or taken out of the drawer, outside of sales. Only on the cashier's own open shift. */
    @Transactional
    public CashMovement addCashMovement(Long shiftId, CashMovementRequest request, Cashier cashier) {
        Shift shift = findById(shiftId);
        if (!shift.getCashier().getId().equals(cashier.getId())) {
            throw new PermissionDeniedException("shift.onlyOwnCash");
        }
        if (!"OPEN".equals(shift.getStatus())) {
            throw new ValidationException("shift.alreadyClosed");
        }
        CashMovement.Type type;
        try {
            type = CashMovement.Type.valueOf(request.type() == null ? "" : request.type().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("cash.invalidType");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("payment.amountPositive");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ValidationException("cash.reasonRequired");
        }
        CashMovement movement = cashMovementRepository.save(new CashMovement(shift, cashier, type,
                request.amount().setScale(2, RoundingMode.HALF_UP), request.reason().trim(), LocalDateTime.now()));
        auditService.record(cashier, "CASH_" + type.name(), "SHIFT", shift.getId(), movement.getAmount() + " LEK - " + movement.getReason());
        return movement;
    }

    public List<CashMovement> findCashMovements(Long shiftId) {
        return cashMovementRepository.findByShiftIdOrderByCreatedAt(shiftId);
    }
}

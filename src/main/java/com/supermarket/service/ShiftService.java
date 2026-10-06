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

    public ShiftService(ShiftRepository shiftRepository, SaleRepository saleRepository,
                        CashMovementRepository cashMovementRepository) {
        this.shiftRepository = shiftRepository;
        this.saleRepository = saleRepository;
        this.cashMovementRepository = cashMovementRepository;
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
        return shiftRepository.save(new Shift(cashier, LocalDateTime.now(), openingCash));
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
        shift.setExpectedCash(expectedCash);
        shift.setDifference(closingCash.subtract(expectedCash));
        shift.setStatus("CLOSED");
        return shiftRepository.save(shift);
    }

    /** Money totals of the shift so far: its sales and its drawer movements. */
    @Transactional(readOnly = true)
    public CashTotals totals(Shift shift) {
        return CashTotals.of(
                saleRepository.findByShiftIdOrderByDate(shift.getId()),
                cashMovementRepository.findByShiftIdOrderByCreatedAt(shift.getId()));
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
        return cashMovementRepository.save(new CashMovement(shift, cashier, type,
                request.amount().setScale(2, RoundingMode.HALF_UP), request.reason().trim(), LocalDateTime.now()));
    }

    public List<CashMovement> findCashMovements(Long shiftId) {
        return cashMovementRepository.findByShiftIdOrderByCreatedAt(shiftId);
    }
}

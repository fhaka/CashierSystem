package com.supermarket.service;

import com.supermarket.dto.ShiftCloseRequest;
import com.supermarket.dto.ShiftOpenRequest;
import com.supermarket.model.Cashier;
import com.supermarket.model.Shift;
import com.supermarket.repository.SaleRepository;
import com.supermarket.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final SaleRepository saleRepository;

    public ShiftService(ShiftRepository shiftRepository, SaleRepository saleRepository) {
        this.shiftRepository = shiftRepository;
        this.saleRepository = saleRepository;
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
            throw new IllegalArgumentException("Opening cash cannot be negative");
        }
        findOpenShift(cashier.getId()).ifPresent(shift -> {
            throw new IllegalArgumentException("This cashier already has an open shift");
        });
        return shiftRepository.save(new Shift(cashier, LocalDateTime.now(), openingCash));
    }

    @Transactional
    public Shift closeShift(Long shiftId, ShiftCloseRequest request, Cashier cashier) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Shift not found with id: " + shiftId));
        if (!shift.getCashier().getId().equals(cashier.getId())) {
            throw new com.supermarket.exception.PermissionDeniedException("You can only close your own shift");
        }
        if (!"OPEN".equals(shift.getStatus())) {
            throw new IllegalArgumentException("Shift is already closed");
        }
        BigDecimal closingCash = request.getClosingCash() == null ? BigDecimal.ZERO : request.getClosingCash();
        if (closingCash.signum() < 0) {
            throw new IllegalArgumentException("Closing cash cannot be negative");
        }

        LocalDateTime closedAt = LocalDateTime.now();
        BigDecimal totalSales = saleRepository.sumTotalByCashierAndDateBetween(
                shift.getCashier().getId(),
                shift.getOpenedAt(),
                closedAt
        );
        BigDecimal expectedCash = shift.getOpeningCash().add(totalSales);

        shift.setClosedAt(closedAt);
        shift.setClosingCash(closingCash);
        shift.setTotalSales(totalSales);
        shift.setExpectedCash(expectedCash);
        shift.setDifference(closingCash.subtract(expectedCash));
        shift.setStatus("CLOSED");
        return shiftRepository.save(shift);
    }
}

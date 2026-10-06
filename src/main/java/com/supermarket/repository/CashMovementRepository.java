package com.supermarket.repository;

import com.supermarket.model.CashMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {

    List<CashMovement> findByShiftIdOrderByCreatedAt(Long shiftId);

    List<CashMovement> findByCreatedAtBetweenOrderByCreatedAt(LocalDateTime from, LocalDateTime to);
}

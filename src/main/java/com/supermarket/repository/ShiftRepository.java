package com.supermarket.repository;

import com.supermarket.model.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findFirstByCashierIdAndStatusOrderByOpenedAtDesc(Long cashierId, String status);

    List<Shift> findByCashierIdOrderByOpenedAtDesc(Long cashierId);

    List<Shift> findAllByOrderByOpenedAtDesc();
}

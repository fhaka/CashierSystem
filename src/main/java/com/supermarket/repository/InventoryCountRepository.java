package com.supermarket.repository;

import com.supermarket.model.InventoryCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryCountRepository extends JpaRepository<InventoryCount, Long> {

    Optional<InventoryCount> findFirstByStatusOrderByIdDesc(InventoryCount.Status status);

    List<InventoryCount> findTop20ByOrderByIdDesc();
}

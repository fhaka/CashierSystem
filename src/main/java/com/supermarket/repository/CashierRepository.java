package com.supermarket.repository;

import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface CashierRepository extends JpaRepository<Cashier, Long> {
    boolean existsByUsernameIgnoreCase(String username);
    Optional<Cashier> findByUsernameIgnoreCase(String username);
    long countByRoleAndActiveTrue(CashierRole role);
    List<Cashier> findAllByOrderByFullNameAsc();
}

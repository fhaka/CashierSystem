package com.supermarket.repository;

import com.supermarket.model.Cashier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CashierRepository extends JpaRepository<Cashier, Long> {
    boolean existsByUsernameIgnoreCase(String username);
    Optional<Cashier> findByUsernameIgnoreCase(String username);
}

package com.supermarket.repository;

import com.supermarket.model.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findFirstByCashierIdAndStatusAndSlotOrderByIdDesc(Long cashierId, Cart.Status status, Integer slot);

    List<Cart> findByCashierIdAndStatusOrderBySlot(Long cashierId, Cart.Status status);

    List<Cart> findByStatusOrderByUpdatedAtDesc(Cart.Status status);
}

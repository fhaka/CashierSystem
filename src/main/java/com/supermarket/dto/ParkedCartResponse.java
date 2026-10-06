package com.supermarket.dto;

import com.supermarket.model.Cart;
import com.supermarket.model.CartItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ParkedCartResponse(Long id, String label, String cashierName, int lines, BigDecimal total, LocalDateTime parkedAt) {

    public static ParkedCartResponse from(Cart cart) {
        return new ParkedCartResponse(
                cart.getId(),
                cart.getLabel(),
                cart.getCashier().getFullName(),
                cart.getItems().size(),
                cart.getItems().stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                cart.getUpdatedAt()
        );
    }
}

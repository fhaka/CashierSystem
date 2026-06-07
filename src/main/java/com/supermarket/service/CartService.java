package com.supermarket.service;

import com.supermarket.dto.CartItemRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.model.CartItem;
import com.supermarket.model.Product;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class CartService {

    private final ProductService productService;
    private final List<CartItem> cart = new ArrayList<>();

    public CartService(ProductService productService) {
        this.productService = productService;
    }

    public synchronized List<CartItem> getCart() {
        return List.copyOf(cart);
    }

    public synchronized List<CartItem> addToCart(CartItemRequest request) {
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        Product product = resolveProduct(request);
        int quantityAlreadyInCart = cart.stream()
                .filter(item -> item.getProductId().equals(product.getId()))
                .mapToInt(CartItem::getQuantity)
                .sum();

        if (product.getStock() < quantityAlreadyInCart + request.getQuantity()) {
            throw new InsufficientStockException("Not enough stock for product: " + product.getName());
        }

        cart.stream()
                .filter(item -> item.getProductId().equals(product.getId()))
                .findFirst()
                .ifPresentOrElse(
                        item -> item.setQuantity(item.getQuantity() + request.getQuantity()),
                        () -> cart.add(new CartItem(product.getId(), product.getName(), product.getBarcode(), request.getQuantity(), product.getPrice(), product.getTaxRate(), product.getUnit()))
                );
        return getCart();
    }

    public synchronized List<CartItem> updateCartItem(Long productId, CartItemRequest request) {
        CartItem cartItem = cart.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Cart item not found with product id: " + productId));

        Product product = productService.findById(productId);
        if (request.getQuantity() != null) {
            if (request.getQuantity() <= 0) {
                cart.remove(cartItem);
                return getCart();
            }
            if (product.getStock() < request.getQuantity()) {
                throw new InsufficientStockException("Not enough stock for product: " + product.getName());
            }
            cartItem.setQuantity(request.getQuantity());
        }
        if (request.getPrice() != null) {
            if (request.getPrice().signum() <= 0) {
                throw new IllegalArgumentException("Price must be greater than zero");
            }
            cartItem.setPrice(request.getPrice());
        }
        return getCart();
    }

    public synchronized BigDecimal calculateSubtotal() {
        return cart.stream()
                .map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public synchronized void clear() {
        cart.clear();
    }

    private Product resolveProduct(CartItemRequest request) {
        if (request.getProductId() != null) {
            return productService.findById(request.getProductId());
        }
        if (request.getBarcode() != null && !request.getBarcode().isBlank()) {
            return productService.findByBarcode(request.getBarcode().trim());
        }
        throw new IllegalArgumentException("Product id or barcode is required");
    }
}

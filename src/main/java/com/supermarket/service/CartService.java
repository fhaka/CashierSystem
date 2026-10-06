package com.supermarket.service;

import com.supermarket.dto.CartItemRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.model.CartItem;
import com.supermarket.model.Product;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CartService {

    private final ProductService productService;
    private final ConcurrentMap<Long, List<CartItem>> cartsByCashier = new ConcurrentHashMap<>();

    public CartService(ProductService productService) {
        this.productService = productService;
    }

    public List<CartItem> getCart(Long cashierId) {
        List<CartItem> cart = cartFor(cashierId);
        synchronized (cart) {
            return List.copyOf(cart);
        }
    }

    public List<CartItem> addToCart(Long cashierId, CartItemRequest request) {
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        Product product = resolveProduct(request);
        List<CartItem> cart = cartFor(cashierId);
        synchronized (cart) {
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
            return List.copyOf(cart);
        }
    }

    public List<CartItem> updateCartItem(Long cashierId, Long productId, CartItemRequest request, boolean allowPriceEdit) {
        List<CartItem> cart = cartFor(cashierId);
        synchronized (cart) {
            CartItem cartItem = cart.stream()
                    .filter(item -> item.getProductId().equals(productId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Cart item not found with product id: " + productId));

            Product product = productService.findById(productId);
            if (request.getQuantity() != null) {
                if (request.getQuantity() <= 0) {
                    cart.remove(cartItem);
                    return List.copyOf(cart);
                }
                if (product.getStock() < request.getQuantity()) {
                    throw new InsufficientStockException("Not enough stock for product: " + product.getName());
                }
                cartItem.setQuantity(request.getQuantity());
            }
            if (request.getPrice() != null && request.getPrice().compareTo(cartItem.getPrice()) != 0) {
                if (!allowPriceEdit) {
                    throw new PermissionDeniedException("Cashiers cannot change product prices");
                }
                if (request.getPrice().signum() <= 0) {
                    throw new IllegalArgumentException("Price must be greater than zero");
                }
                cartItem.setPrice(request.getPrice());
            }
            return List.copyOf(cart);
        }
    }

    public BigDecimal calculateSubtotal(Long cashierId) {
        List<CartItem> cart = cartFor(cashierId);
        synchronized (cart) {
            return cart.stream()
                    .map(CartItem::getLineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public void clear(Long cashierId) {
        List<CartItem> cart = cartsByCashier.remove(cashierId);
        if (cart != null) {
            synchronized (cart) {
                cart.clear();
            }
        }
    }

    private List<CartItem> cartFor(Long cashierId) {
        if (cashierId == null) {
            throw new IllegalArgumentException("Cashier id is required for the cart");
        }
        return cartsByCashier.computeIfAbsent(cashierId, ignored -> new ArrayList<>());
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

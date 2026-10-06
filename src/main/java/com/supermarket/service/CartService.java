package com.supermarket.service;

import com.supermarket.dto.CartItemRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cart;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.repository.CartRepository;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The cart on each till, stored in the database so it survives a restart. Each cashier has one OPEN cart;
 * other carts can be PARKED and resumed later, also from another till.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;

    public CartService(CartRepository cartRepository, ProductService productService) {
        this.cartRepository = cartRepository;
        this.productService = productService;
    }

    @Transactional(readOnly = true)
    public List<CartItem> getCart(Cashier cashier) {
        return openCart(cashier).map(cart -> List.copyOf(cart.getItems())).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateSubtotal(Cashier cashier) {
        return openCart(cashier).map(Cart::getSubtotal).orElse(BigDecimal.ZERO);
    }

    @Transactional
    public List<CartItem> addToCart(Cashier cashier, CartItemRequest request) {
        Product product = resolveProduct(request);
        if (!product.isActive()) {
            throw new ValidationException("product.inactive", product.getName());
        }
        BigDecimal quantity = Quantities.requirePositive(request.getQuantity(), product.getUnit());
        Cart cart = openCartOrCreate(cashier);
        Optional<CartItem> existing = cart.findItem(product.getId());
        BigDecimal newQuantity = existing.map(item -> item.getQuantity().add(quantity)).orElse(quantity);
        requireStock(product, newQuantity);

        existing.ifPresentOrElse(
                item -> item.setQuantity(newQuantity),
                () -> cart.addItem(new CartItem(product, quantity))
        );
        return touch(cart);
    }

    /** Sets a line's quantity (zero or less removes the line) and, for managers, its price. */
    @Transactional
    public List<CartItem> updateCartItem(Cashier cashier, Long productId, CartItemRequest request) {
        Cart cart = openCart(cashier).orElseThrow(() -> new ValidationException("cart.itemNotFound", productId));
        CartItem item = cart.findItem(productId).orElseThrow(() -> new ValidationException("cart.itemNotFound", productId));

        if (request.getQuantity() != null) {
            if (request.getQuantity().signum() <= 0) {
                cart.removeItem(item);
                return touch(cart);
            }
            BigDecimal quantity = Quantities.requirePositive(request.getQuantity(), item.getUnit());
            requireStock(productService.findById(productId), quantity);
            item.setQuantity(quantity);
        }
        if (request.getPrice() != null && request.getPrice().compareTo(item.getPrice()) != 0) {
            if (!cashier.getRole().isOperationalManager()) {
                throw new PermissionDeniedException("cart.cannotChangePrice");
            }
            if (request.getPrice().signum() <= 0) {
                throw new ValidationException("cart.pricePositive");
            }
            item.setPrice(request.getPrice());
        }
        return touch(cart);
    }

    @Transactional
    public void clear(Cashier cashier) {
        openCart(cashier).ifPresent(cartRepository::delete);
    }

    /** Puts the cart on screen aside so the till can serve the next customer. */
    @Transactional
    public Cart park(Cashier cashier, String label) {
        Cart cart = openCart(cashier)
                .filter(open -> !open.getItems().isEmpty())
                .orElseThrow(() -> new ValidationException("cart.nothingToPark"));
        cart.setStatus(Cart.Status.PARKED);
        cart.setLabel(label == null || label.isBlank() ? null : label.trim());
        cart.setUpdatedAt(LocalDateTime.now());
        return cart;
    }

    @Transactional(readOnly = true)
    public List<Cart> findParked() {
        return cartRepository.findByStatusOrderByUpdatedAtDesc(Cart.Status.PARKED);
    }

    /** Brings a parked cart to this till. The till's own cart must be empty first. */
    @Transactional
    public List<CartItem> resume(Cashier cashier, Long cartId) {
        Optional<Cart> current = openCart(cashier);
        if (current.isPresent() && !current.get().getItems().isEmpty()) {
            throw new ValidationException("cart.notEmpty");
        }
        Cart parked = cartRepository.findById(cartId)
                .filter(cart -> cart.getStatus() == Cart.Status.PARKED)
                .orElseThrow(() -> new ValidationException("cart.parkedNotFound"));
        current.ifPresent(cartRepository::delete);
        cartRepository.flush();
        parked.setStatus(Cart.Status.OPEN);
        parked.setCashier(cashier);
        return touch(parked);
    }

    /** The cart to sell, used by checkout inside its own transaction. */
    Optional<Cart> openCart(Cashier cashier) {
        return cartRepository.findFirstByCashierIdAndStatusOrderByIdDesc(cashier.getId(), Cart.Status.OPEN);
    }

    void delete(Cart cart) {
        cartRepository.delete(cart);
    }

    private Cart openCartOrCreate(Cashier cashier) {
        return openCart(cashier).orElseGet(() -> cartRepository.save(new Cart(cashier, LocalDateTime.now())));
    }

    private List<CartItem> touch(Cart cart) {
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.saveAndFlush(cart);
        return List.copyOf(cart.getItems());
    }

    private void requireStock(Product product, BigDecimal quantity) {
        if (product.getStock().compareTo(quantity) < 0) {
            throw new InsufficientStockException(product.getName());
        }
    }

    private Product resolveProduct(CartItemRequest request) {
        if (request.getProductId() != null) {
            return productService.findById(request.getProductId());
        }
        if (request.getBarcode() != null && !request.getBarcode().isBlank()) {
            return productService.findByBarcode(request.getBarcode().trim());
        }
        throw new ValidationException("cart.productRequired");
    }
}

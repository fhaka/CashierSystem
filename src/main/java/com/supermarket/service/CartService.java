package com.supermarket.service;

import com.supermarket.dto.CartItemRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cart;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.repository.CartRepository;
import com.supermarket.util.Quantities;
import org.springframework.beans.factory.annotation.Value;
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
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final boolean voidsNeedApproval;

    public CartService(
            CartRepository cartRepository,
            ProductService productService,
            ApprovalService approvalService,
            AuditService auditService,
            @Value("${pos.approval.voids:true}") boolean voidsNeedApproval
    ) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.voidsNeedApproval = voidsNeedApproval;
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

    /**
     * Sets a line's quantity (zero or less removes the line) and its price. Lowering a quantity or removing a
     * line is a void; a cashier needs a manager's PIN for voids (if pos.approval.voids) and for price changes.
     */
    @Transactional
    public List<CartItem> updateCartItem(Cashier cashier, Long productId, CartItemRequest request, String approvalPin) {
        Cart cart = openCart(cashier).orElseThrow(() -> new ValidationException("cart.itemNotFound", productId));
        CartItem item = cart.findItem(productId).orElseThrow(() -> new ValidationException("cart.itemNotFound", productId));

        BigDecimal oldQuantity = item.getQuantity();
        BigDecimal newQuantity = oldQuantity;
        if (request.getQuantity() != null) {
            newQuantity = request.getQuantity().signum() <= 0
                    ? BigDecimal.ZERO
                    : Quantities.requirePositive(request.getQuantity(), item.getUnit());
        }
        boolean isVoid = newQuantity.compareTo(oldQuantity) < 0;
        boolean priceChange = request.getPrice() != null && request.getPrice().compareTo(item.getPrice()) != 0;
        if (priceChange && request.getPrice().signum() <= 0) {
            throw new ValidationException("cart.pricePositive");
        }

        Cashier approver = null;
        if (priceChange) {
            approver = approvalService.approve(cashier, approvalPin, "PRICE_OVERRIDE");
        } else if (isVoid && voidsNeedApproval) {
            approver = approvalService.approve(cashier, approvalPin, "CART_LINE_VOID");
        }

        if (isVoid) {
            auditService.record(cashier, approver, "CART_LINE_VOID", "PRODUCT", productId,
                    item.getProductName() + ": " + oldQuantity.stripTrailingZeros().toPlainString()
                            + " -> " + newQuantity.stripTrailingZeros().toPlainString());
        }
        if (newQuantity.signum() == 0) {
            cart.removeItem(item);
            return touch(cart);
        }
        if (newQuantity.compareTo(oldQuantity) > 0) {
            requireStock(productService.findById(productId), newQuantity);
        }
        item.setQuantity(newQuantity);
        if (priceChange) {
            auditService.record(cashier, approver, "PRICE_OVERRIDE", "PRODUCT", productId,
                    item.getProductName() + ": " + item.getPrice() + " -> " + request.getPrice());
            item.setPrice(request.getPrice());
        }
        return touch(cart);
    }

    /** Empties the till's cart. With items in it this is a void of the whole cart. */
    @Transactional
    public void clear(Cashier cashier, String approvalPin) {
        Optional<Cart> cart = openCart(cashier);
        if (cart.isEmpty()) {
            return;
        }
        if (!cart.get().getItems().isEmpty()) {
            Cashier approver = voidsNeedApproval ? approvalService.approve(cashier, approvalPin, "CART_CLEARED") : null;
            auditService.record(cashier, approver, "CART_CLEARED", "CART", cart.get().getId(),
                    cart.get().getItems().size() + " lines, " + cart.get().getSubtotal() + " LEK");
        }
        cartRepository.delete(cart.get());
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

package com.supermarket.service;

import com.supermarket.dto.CartItemRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cart;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.Customer;
import com.supermarket.model.Product;
import com.supermarket.model.ProductPackage;
import com.supermarket.repository.CartRepository;
import com.supermarket.util.Quantities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final ScaleBarcodes scaleBarcodes;
    private final CustomerService customerService;
    private final PackageService packageService;
    private final boolean voidsNeedApproval;
    private final BigDecimal cashierDiscountLimit;

    public CartService(
            CartRepository cartRepository,
            ProductService productService,
            ApprovalService approvalService,
            AuditService auditService,
            ScaleBarcodes scaleBarcodes,
            CustomerService customerService,
            PackageService packageService,
            @Value("${pos.approval.voids:true}") boolean voidsNeedApproval,
            @Value("${pos.discount.cashier-limit-percent:0}") BigDecimal cashierDiscountLimit
    ) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.scaleBarcodes = scaleBarcodes;
        this.customerService = customerService;
        this.packageService = packageService;
        this.voidsNeedApproval = voidsNeedApproval;
        this.cashierDiscountLimit = cashierDiscountLimit;
    }

    @Transactional(readOnly = true)
    public List<CartItem> getCart(Cashier cashier) {
        return openCart(cashier).map(cart -> List.copyOf(cart.getItems())).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateSubtotal(Cashier cashier) {
        return openCart(cashier).map(Cart::getSubtotal).orElse(BigDecimal.ZERO);
    }

    /**
     * Adds a product, a box (its barcode or packageId) or a scale label. A box line counts boxes; a box and single
     * pieces of the same product are separate lines, and the stock check counts both.
     */
    @Transactional
    public List<CartItem> addToCart(Cashier cashier, CartItemRequest request) {
        Optional<ProductPackage> box = packageFor(request);
        if (box.isPresent()) {
            return addBox(cashier, box.get(), request.getQuantity() == null ? BigDecimal.ONE : request.getQuantity());
        }
        Product product;
        BigDecimal quantity;
        Optional<ScaleBarcodes.ScaleCode> scaleCode = scaleCodeFor(request);
        if (scaleCode.isPresent()) {
            // A label printed by the scale: the barcode carries the product and its weight (or price).
            product = productService.findOptionalByBarcode(scaleCode.get().productBarcode())
                    .orElseThrow(() -> new ValidationException("product.barcodeNotFound", request.getBarcode().trim()));
            if (scaleBarcodes.mode() == ScaleBarcodes.Mode.WEIGHT && !Quantities.KILOGRAMS.equals(product.getUnit())) {
                throw new ValidationException("scale.notWeighed", product.getName());
            }
            quantity = Quantities.requirePositive(scaleBarcodes.quantity(scaleCode.get(), product.getPrice()), product.getUnit());
        } else {
            product = resolveProduct(request);
            quantity = Quantities.requirePositive(request.getQuantity(), product.getUnit());
        }
        if (!product.isActive()) {
            throw new ValidationException("product.inactive", product.getName());
        }
        Cart cart = openCartOrCreate(cashier);
        Optional<CartItem> existing = cart.findItem(product.getId(), null);
        requireStock(product, cart.piecesOf(product.getId()).add(quantity));

        existing.ifPresentOrElse(
                item -> item.setQuantity(item.getQuantity().add(quantity)),
                () -> cart.addItem(new CartItem(product, quantity))
        );
        return touch(cart);
    }

    private List<CartItem> addBox(Cashier cashier, ProductPackage box, BigDecimal boxes) {
        Product product = box.getProduct();
        if (!product.isActive() || !box.isActive()) {
            throw new ValidationException("product.inactive", product.getName() + " (" + box.getName() + ")");
        }
        BigDecimal count = Quantities.requirePositive(boxes, Quantities.PIECES);
        Cart cart = openCartOrCreate(cashier);
        requireStock(product, cart.piecesOf(product.getId()).add(count.multiply(BigDecimal.valueOf(box.getPieces()))));
        cart.findItem(product.getId(), box.getId()).ifPresentOrElse(
                item -> item.setQuantity(item.getQuantity().add(count)),
                () -> cart.addItem(new CartItem(box, count)));
        return touch(cart);
    }

    /**
     * Sets a line's quantity (zero or less removes the line) and its price. Lowering a quantity or removing a
     * line is a void; a cashier needs a manager's PIN for voids (if pos.approval.voids) and for price changes.
     */
    @Transactional
    public List<CartItem> updateCartItem(Cashier cashier, Long productId, CartItemRequest request, String approvalPin) {
        Cart cart = openCart(cashier).orElseThrow(() -> new ValidationException("cart.itemNotFound", productId));
        CartItem item = cart.findItem(productId, null).orElseThrow(() -> new ValidationException("cart.itemNotFound", productId));
        return updateLine(cashier, cart, item, request, approvalPin);
    }

    /** The same for any line, by its id (lines of boxes have no line of their own per product). */
    @Transactional
    public List<CartItem> updateCartLine(Cashier cashier, Long lineId, CartItemRequest request, String approvalPin) {
        Cart cart = openCart(cashier).orElseThrow(() -> new ValidationException("cart.lineNotFound"));
        CartItem item = cart.findLine(lineId).orElseThrow(() -> new ValidationException("cart.lineNotFound"));
        return updateLine(cashier, cart, item, request, approvalPin);
    }

    private List<CartItem> updateLine(Cashier cashier, Cart cart, CartItem item, CartItemRequest request, String approvalPin) {
        Long productId = item.getProductId();

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
                    lineName(item) + ": " + oldQuantity.stripTrailingZeros().toPlainString()
                            + " -> " + newQuantity.stripTrailingZeros().toPlainString());
        }
        if (newQuantity.signum() == 0) {
            cart.removeItem(item);
            return touch(cart);
        }
        if (newQuantity.compareTo(oldQuantity) > 0) {
            BigDecimal perUnit = item.getPiecesPerUnit() == null ? BigDecimal.ONE : BigDecimal.valueOf(item.getPiecesPerUnit());
            requireStock(productService.findById(productId),
                    cart.piecesOf(productId).add(newQuantity.subtract(oldQuantity).multiply(perUnit)));
        }
        item.setQuantity(newQuantity);
        if (priceChange) {
            auditService.record(cashier, approver, "PRICE_OVERRIDE", "PRODUCT", productId,
                    lineName(item) + ": " + item.getPrice() + " -> " + request.getPrice());
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

    /** Attaches a customer (by id or loyalty card number) to the cart on screen; null removes them. */
    @Transactional
    public Cart setCustomer(Cashier cashier, Long customerId, String cardNumber) {
        Cart cart = openCartOrCreate(cashier);
        if (customerId == null && (cardNumber == null || cardNumber.isBlank())) {
            cart.setCustomer(null);
        } else {
            Customer customer = customerId != null ? customerService.find(customerId) : customerService.findByCard(cardNumber);
            if (!customer.isActive()) {
                throw new ValidationException("customer.inactive", customer.getFullName());
            }
            cart.setCustomer(customer);
        }
        touch(cart);
        return cart;
    }

    /**
     * A percentage off the whole cart. Up to pos.discount.cashier-limit-percent a cashier gives it alone;
     * above that a manager's PIN is needed. 0 removes the discount.
     */
    @Transactional
    public Cart setManualDiscount(Cashier cashier, BigDecimal percent, String approvalPin) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ValidationException("discount.invalidPercent");
        }
        Cart cart = openCart(cashier)
                .filter(open -> !open.getItems().isEmpty())
                .orElseThrow(() -> new ValidationException("cart.empty"));
        if (percent.signum() == 0) {
            cart.setManualDiscountPercent(null);
            touch(cart);
            return cart;
        }
        Cashier approver = percent.compareTo(cashierDiscountLimit) > 0
                ? approvalService.approve(cashier, approvalPin, "MANUAL_DISCOUNT")
                : null;
        cart.setManualDiscountPercent(percent.setScale(2, RoundingMode.HALF_UP));
        auditService.record(cashier, approver, "MANUAL_DISCOUNT", "CART", cart.getId(),
                percent.stripTrailingZeros().toPlainString() + "% on " + cart.getSubtotal() + " LEK");
        touch(cart);
        return cart;
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

    /** A scanned barcode that is not a product of its own but a scale label. */
    private Optional<ScaleBarcodes.ScaleCode> scaleCodeFor(CartItemRequest request) {
        if (request.getProductId() != null || request.getBarcode() == null) {
            return Optional.empty();
        }
        String barcode = request.getBarcode().trim();
        return productService.findOptionalByBarcode(barcode).isPresent() ? Optional.empty() : scaleBarcodes.parse(barcode);
    }

    /** The box asked for: by packageId, or a scanned barcode that belongs to a box. */
    private Optional<ProductPackage> packageFor(CartItemRequest request) {
        if (request.getPackageId() != null) {
            return Optional.of(packageService.find(request.getPackageId()));
        }
        if (request.getProductId() != null || request.getBarcode() == null || request.getBarcode().isBlank()) {
            return Optional.empty();
        }
        return packageService.findByBarcode(request.getBarcode().trim());
    }

    private static String lineName(CartItem item) {
        return item.getPackageName() == null ? item.getProductName() : item.getProductName() + " (" + item.getPackageName() + ")";
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

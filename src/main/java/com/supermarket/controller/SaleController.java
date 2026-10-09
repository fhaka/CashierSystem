package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.CartItemRequest;
import com.supermarket.dto.CartSummary;
import com.supermarket.dto.CheckoutRequest;
import com.supermarket.dto.ParkCartRequest;
import com.supermarket.dto.ParkedCartResponse;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.dto.SalesPage;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.dto.SaleLogRequest;
import com.supermarket.model.CartItem;
import com.supermarket.model.Sale;
import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import com.supermarket.service.CartService;
import com.supermarket.service.SaleService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sales")
public class SaleController {

    private final CartService cartService;
    private final SaleService saleService;
    private final SessionService sessionService;

    public SaleController(CartService cartService, SaleService saleService, SessionService sessionService) {
        this.cartService = cartService;
        this.saleService = saleService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<SalesPage> findSales(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String cashierName,
            @RequestParam(required = false) String invoice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Cashier cashier = sessionService.requireUser(token);
        boolean manager = cashier.getRole().isOperationalManager();
        return ApiResponse.ok("Sales history loaded", saleService.search(from, to, manager ? null : cashier.getId(),
                manager ? cashierName : null, invoice, page, size));
    }

    /** One sale with its lines and payments. Cashiers can only open their own sales. */
    @GetMapping("/{id}")
    public ApiResponse<Sale> findSale(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        Cashier cashier = sessionService.requireUser(token);
        Sale sale = saleService.findById(id);
        if (!cashier.getRole().isOperationalManager() && (sale.getCashier() == null || !sale.getCashier().getId().equals(cashier.getId()))) {
            throw new PermissionDeniedException("sale.onlyOwn");
        }
        return ApiResponse.ok("Sale loaded", sale);
    }

    /** The receipt of an earlier sale, marked as a copy, for reprinting. Same access as the sale itself. */
    @GetMapping("/{id}/receipt")
    public ApiResponse<Map<String, String>> receiptCopy(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                        @PathVariable Long id) {
        Cashier cashier = sessionService.requireUser(token);
        Sale sale = saleService.findById(id);
        if (!cashier.getRole().isOperationalManager() && (sale.getCashier() == null || !sale.getCashier().getId().equals(cashier.getId()))) {
            throw new PermissionDeniedException("sale.onlyOwn");
        }
        return ApiResponse.ok("Receipt loaded", Map.of("receiptText", saleService.receiptCopy(id, cashier)));
    }

    @GetMapping("/cart")
    public ApiResponse<List<CartItem>> getCart(@RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart loaded", cartService.getCart(cashier, tab));
    }

    @PostMapping("/cart")
    public ApiResponse<List<CartItem>> addToCart(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestBody CartItemRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Item added to cart", cartService.addToCart(cashier, tab, request));
    }

    @PutMapping("/cart/{productId}")
    public ApiResponse<List<CartItem>> updateCartItem(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestHeader(value = "X-Approval-Pin", required = false) String approvalPin,
            @PathVariable Long productId,
            @RequestBody CartItemRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart item updated", cartService.updateCartItem(cashier, tab, productId, request, approvalPin));
    }

    /** Quantity or price of one cart line, by its lineId (needed for lines of boxes). */
    @PutMapping("/cart/lines/{lineId}")
    public ApiResponse<List<CartItem>> updateCartLine(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestHeader(value = "X-Approval-Pin", required = false) String approvalPin,
            @PathVariable Long lineId,
            @RequestBody CartItemRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart item updated", cartService.updateCartLine(cashier, tab, lineId, request, approvalPin));
    }

    @GetMapping("/cart/subtotal")
    public ApiResponse<BigDecimal> getSubtotal(@RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart subtotal calculated with streams", cartService.calculateSubtotal(cashier, tab));
    }

    @GetMapping("/cart/summary")
    public ApiResponse<CartSummary> getCartSummary(@RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart summary", saleService.cartSummary(cashier, tab));
    }

    /** Attaches a customer to the cart on screen: {"customerId": 5} or {"cardNumber": "..."}; {} removes them. */
    @PutMapping("/cart/customer")
    public ApiResponse<CartSummary> setCustomer(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestBody Map<String, String> body
    ) {
        Cashier cashier = sessionService.requireUser(token);
        String customerId = body.get("customerId");
        cartService.setCustomer(cashier, tab, customerId == null || customerId.isBlank() ? null : Long.valueOf(customerId), body.get("cardNumber"));
        return ApiResponse.ok("Customer set", saleService.cartSummary(cashier, tab));
    }

    /** Percentage off the whole cart: {"percent": 10}. Above the cashier limit it needs a manager PIN. */
    @PutMapping("/cart/discount")
    public ApiResponse<CartSummary> setDiscount(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestHeader(value = "X-Approval-Pin", required = false) String approvalPin,
            @RequestBody Map<String, BigDecimal> body
    ) {
        Cashier cashier = sessionService.requireUser(token);
        cartService.setManualDiscount(cashier, tab, body.get("percent"), approvalPin);
        return ApiResponse.ok("Discount set", saleService.cartSummary(cashier, tab));
    }

    @DeleteMapping("/cart")
    public ApiResponse<Void> clearCart(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestHeader(value = "X-Approval-Pin", required = false) String approvalPin
    ) {
        Cashier cashier = sessionService.requireUser(token);
        cartService.clear(cashier, tab, approvalPin);
        return ApiResponse.ok("Cart cleared", null);
    }

    /** Puts the cart on screen aside (customer went to fetch something) so the till can serve someone else. */
    @PostMapping("/cart/park")
    public ApiResponse<ParkedCartResponse> parkCart(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestBody(required = false) ParkCartRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart parked", ParkedCartResponse.from(cartService.park(cashier, tab, request == null ? null : request.label())));
    }

    @GetMapping("/carts/parked")
    public ApiResponse<List<ParkedCartResponse>> findParkedCarts(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Parked carts loaded", cartService.findParked().stream().map(ParkedCartResponse::from).toList());
    }

    @PostMapping("/carts/{cartId}/resume")
    public ApiResponse<List<CartItem>> resumeCart(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @PathVariable Long cartId
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Cart resumed", cartService.resume(cashier, tab, cartId));
    }

    /** The three tabs of the cashier's till: lines and total of each, for the tab buttons. */
    @GetMapping("/cart/tabs")
    public ApiResponse<List<Map<String, Object>>> tabs(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        Cashier cashier = sessionService.requireUser(token);
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (int tab = 1; tab <= CartService.TABS; tab++) {
            CartSummary summary = saleService.cartSummary(cashier, tab);
            List<CartItem> items = cartService.getCart(cashier, tab);
            tabs.add(Map.of("tab", tab, "lines", items.size(), "total", summary.totalAmount(),
                    "customer", summary.customer() == null ? "" : summary.customer().getFullName()));
        }
        return ApiResponse.ok("Till tabs", tabs);
    }

    @GetMapping("/next-invoice-number")
    public ApiResponse<String> nextInvoiceNumber(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Next invoice number", saleService.peekNextInvoiceNumber());
    }

    @PostMapping("/checkout")
    public ApiResponse<ReceiptResponse> checkout(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Cart-Slot", required = false) Integer tab,
            @RequestBody(required = false) CheckoutRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Checkout completed", saleService.checkout(cashier, tab, request));
    }

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> getSaleLogs(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Sale logs loaded with JDBC", saleService.findSaleLogs());
    }

    @GetMapping("/logs/{id}")
    public ApiResponse<Map<String, Object>> getSaleLogById(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Sale log loaded with JDBC", saleService.findSaleLogById(id));
    }

    @PostMapping("/logs")
    public ApiResponse<Map<String, Object>> createSaleLog(@RequestHeader(value = "X-Auth-Token", required = false) String token, @RequestBody SaleLogRequest request) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Sale log created with JDBC", saleService.createSaleLog(request));
    }

    @PutMapping("/logs/{id}")
    public ApiResponse<Map<String, Object>> updateSaleLog(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id, @RequestBody SaleLogRequest request) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Sale log updated with JDBC", saleService.updateSaleLog(id, request));
    }

    @DeleteMapping("/logs/{id}")
    public ApiResponse<Void> deleteSaleLog(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        saleService.deleteSaleLog(id);
        return ApiResponse.ok("Sale log deleted with JDBC", null);
    }
}

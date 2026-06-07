package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.CartItemRequest;
import com.supermarket.dto.CheckoutRequest;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.dto.SaleLogRequest;
import com.supermarket.model.CartItem;
import com.supermarket.model.Sale;
import com.supermarket.service.CartService;
import com.supermarket.service.SaleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sales")
public class SaleController {

    private final CartService cartService;
    private final SaleService saleService;

    public SaleController(CartService cartService, SaleService saleService) {
        this.cartService = cartService;
        this.saleService = saleService;
    }

    @GetMapping
    public ApiResponse<List<Sale>> findAllSales() {
        return ApiResponse.ok("Sales history loaded", saleService.findAll());
    }

    @GetMapping("/cart")
    public ApiResponse<List<CartItem>> getCart() {
        return ApiResponse.ok("Cart loaded", cartService.getCart());
    }

    @PostMapping("/cart")
    public ApiResponse<List<CartItem>> addToCart(@RequestBody CartItemRequest request) {
        return ApiResponse.ok("Item added to cart", cartService.addToCart(request));
    }

    @PutMapping("/cart/{productId}")
    public ApiResponse<List<CartItem>> updateCartItem(@PathVariable Long productId, @RequestBody CartItemRequest request) {
        return ApiResponse.ok("Cart item updated", cartService.updateCartItem(productId, request));
    }

    @GetMapping("/cart/subtotal")
    public ApiResponse<BigDecimal> getSubtotal() {
        return ApiResponse.ok("Cart subtotal calculated with streams", cartService.calculateSubtotal());
    }

    @DeleteMapping("/cart")
    public ApiResponse<Void> clearCart() {
        cartService.clear();
        return ApiResponse.ok("Cart cleared", null);
    }

    @PostMapping("/checkout")
    public ApiResponse<ReceiptResponse> checkout(@RequestBody(required = false) CheckoutRequest request) {
        return ApiResponse.ok("Checkout completed", saleService.checkout(request));
    }

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> getSaleLogs() {
        return ApiResponse.ok("Sale logs loaded with JDBC", saleService.findSaleLogs());
    }

    @GetMapping("/logs/{id}")
    public ApiResponse<Map<String, Object>> getSaleLogById(@PathVariable Long id) {
        return ApiResponse.ok("Sale log loaded with JDBC", saleService.findSaleLogById(id));
    }

    @PostMapping("/logs")
    public ApiResponse<Map<String, Object>> createSaleLog(@RequestBody SaleLogRequest request) {
        return ApiResponse.ok("Sale log created with JDBC", saleService.createSaleLog(request));
    }

    @PutMapping("/logs/{id}")
    public ApiResponse<Map<String, Object>> updateSaleLog(@PathVariable Long id, @RequestBody SaleLogRequest request) {
        return ApiResponse.ok("Sale log updated with JDBC", saleService.updateSaleLog(id, request));
    }

    @DeleteMapping("/logs/{id}")
    public ApiResponse<Void> deleteSaleLog(@PathVariable Long id) {
        saleService.deleteSaleLog(id);
        return ApiResponse.ok("Sale log deleted with JDBC", null);
    }
}

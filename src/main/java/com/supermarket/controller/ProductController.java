package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ProductRequest;
import com.supermarket.model.Product;
import com.supermarket.service.ProductService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final SessionService sessionService;

    public ProductController(ProductService productService, SessionService sessionService) {
        this.productService = productService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<Product>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Products loaded", productService.findAll());
    }

    @GetMapping("/in-stock")
    public ApiResponse<List<Product>> findInStock(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("In-stock products loaded", productService.findInStock());
    }

    @GetMapping("/search")
    public ApiResponse<List<Product>> search(@RequestHeader(value = "X-Auth-Token", required = false) String token, @RequestParam String query) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Products filtered by name or barcode", productService.search(query));
    }

    @GetMapping("/barcode/{barcode}")
    public ApiResponse<Product> findByBarcode(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable String barcode) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Product loaded by barcode", productService.findByBarcode(barcode));
    }

    @GetMapping("/sorted-by-price")
    public ApiResponse<List<Product>> findSortedByPrice(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Products sorted by price", productService.findSortedByPrice());
    }

    @GetMapping("/lookup")
    public ApiResponse<Map<Long, Product>> findLookupMap(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Product HashMap lookup loaded", productService.findLookupMap());
    }

    @GetMapping("/sorted-map")
    public ApiResponse<TreeMap<String, Product>> findSortedByNameMap(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Product TreeMap loaded", productService.findSortedByNameMap());
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> findById(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Product loaded", productService.findById(id));
    }

    @PostMapping
    public ApiResponse<Product> create(@RequestHeader(value = "X-Auth-Token", required = false) String token, @RequestBody ProductRequest request) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Product created", productService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> update(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id, @RequestBody ProductRequest request) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Product updated", productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        productService.deactivate(id);
        return ApiResponse.ok("Product deactivated", null);
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<Product> activate(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Product activated", productService.activate(id));
    }
}

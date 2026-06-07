package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ProductRequest;
import com.supermarket.model.Product;
import com.supermarket.service.ProductService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ApiResponse<List<Product>> findAll() {
        return ApiResponse.ok("Products loaded", productService.findAll());
    }

    @GetMapping("/in-stock")
    public ApiResponse<List<Product>> findInStock() {
        return ApiResponse.ok("In-stock products loaded", productService.findInStock());
    }

    @GetMapping("/search")
    public ApiResponse<List<Product>> search(@RequestParam String query) {
        return ApiResponse.ok("Products filtered by name or barcode", productService.search(query));
    }

    @GetMapping("/barcode/{barcode}")
    public ApiResponse<Product> findByBarcode(@PathVariable String barcode) {
        return ApiResponse.ok("Product loaded by barcode", productService.findByBarcode(barcode));
    }

    @GetMapping("/sorted-by-price")
    public ApiResponse<List<Product>> findSortedByPrice() {
        return ApiResponse.ok("Products sorted by price", productService.findSortedByPrice());
    }

    @GetMapping("/lookup")
    public ApiResponse<Map<Long, Product>> findLookupMap() {
        return ApiResponse.ok("Product HashMap lookup loaded", productService.findLookupMap());
    }

    @GetMapping("/sorted-map")
    public ApiResponse<TreeMap<String, Product>> findSortedByNameMap() {
        return ApiResponse.ok("Product TreeMap loaded", productService.findSortedByNameMap());
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> findById(@PathVariable Long id) {
        return ApiResponse.ok("Product loaded", productService.findById(id));
    }

    @PostMapping
    public ApiResponse<Product> create(@RequestBody ProductRequest request) {
        return ApiResponse.ok("Product created", productService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> update(@PathVariable Long id, @RequestBody ProductRequest request) {
        return ApiResponse.ok("Product updated", productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.ok("Product deleted", null);
    }
}

package com.supermarket.repository;

import com.supermarket.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByStockGreaterThan(Integer stock);
    List<Product> findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String name, String barcode);
    Optional<Product> findByBarcode(String barcode);
}

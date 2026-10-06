package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.dto.ProductRequest;
import com.supermarket.exception.ProductNotFoundException;
import com.supermarket.model.Category;
import com.supermarket.model.Product;
import com.supermarket.repository.CategoryRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.util.FilterUtil;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final FilterUtil<Product> productFilter = new FilterUtil<>();

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    /** Products the till can sell right now: active and with stock. */
    public List<Product> findInStock() {
        return productFilter.filter(productRepository.findByActiveTrue(), product -> product.getStock().signum() > 0);
    }

    public List<Product> search(String query) {
        if (query == null || query.isBlank()) {
            return productRepository.findByActiveTrue();
        }
        return productFilter.filter(
                productRepository.findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(query.trim(), query.trim()),
                Product::isActive);
    }

    public Product findByBarcode(String barcode) {
        return productRepository.findByBarcode(barcode)
                .orElseThrow(() -> new ValidationException("product.barcodeNotFound", barcode));
    }

    public List<Product> findSortedByPrice() {
        List<Product> products = productRepository.findAll();
        products.sort((first, second) -> first.getPrice().compareTo(second.getPrice()));
        return products;
    }

    public Map<Long, Product> findLookupMap() {
        Map<Long, Product> productsById = new HashMap<>();
        productRepository.findAll().forEach(product -> productsById.put(product.getId(), product));
        return productsById;
    }

    public TreeMap<String, Product> findSortedByNameMap() {
        TreeMap<String, Product> productsByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getName))
                .forEach(product -> productsByName.put(product.getName(), product));
        return productsByName;
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional
    public Product create(ProductRequest request) {
        validateProductRequest(request);
        requireFreeBarcode(request.getBarcode().trim(), null);
        Category category = resolveCategory(request);
        String unit = normalizeUnit(request.getUnit());
        Product product = new Product(request.getName().trim(), request.getBarcode().trim(), request.getPrice(), request.getPurchasePrice(),
                request.getTaxRate(), Quantities.requireStock(request.getStock(), unit), unit, category);
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, ProductRequest request) {
        validateProductRequest(request);
        Product product = findById(id);
        requireFreeBarcode(request.getBarcode().trim(), id);
        String unit = normalizeUnit(request.getUnit());
        product.setName(request.getName().trim());
        product.setBarcode(request.getBarcode().trim());
        product.setPrice(request.getPrice());
        product.setPurchasePrice(request.getPurchasePrice());
        product.setTaxRate(request.getTaxRate());
        product.setStock(Quantities.requireStock(request.getStock(), unit));
        product.setUnit(unit);
        product.setCategory(resolveCategory(request));
        return productRepository.save(product);
    }

    /**
     * Products are never deleted: old sales and purchases keep pointing at them. A deactivated product
     * disappears from the till and can be reactivated later.
     */
    @Transactional
    public void deactivate(Long id) {
        findById(id).setActive(false);
    }

    @Transactional
    public Product activate(Long id) {
        Product product = findById(id);
        product.setActive(true);
        return product;
    }

    private void requireFreeBarcode(String barcode, Long ownId) {
        productRepository.findByBarcode(barcode)
                .filter(other -> !other.getId().equals(ownId))
                .ifPresent(other -> {
                    throw new ValidationException(other.isActive() ? "product.barcodeExists" : "product.barcodeInactive", other.getName());
                });
    }

    private Category resolveCategory(ProductRequest request) {
        if (request.getCategoryId() != null) {
            return categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ValidationException("category.notFound", request.getCategoryId()));
        }
        String categoryName = request.getCategoryName() == null || request.getCategoryName().isBlank()
                ? "General"
                : request.getCategoryName().trim();
        return categoryRepository.findByNameIgnoreCase(categoryName)
                .orElseGet(() -> categoryRepository.save(new Category(categoryName)));
    }

    private void validateProductRequest(ProductRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new ValidationException("product.nameRequired");
        }
        if (request.getBarcode() == null || request.getBarcode().isBlank()) {
            throw new ValidationException("product.barcodeRequired");
        }
        if (request.getPrice() == null || request.getPrice().signum() <= 0) {
            throw new ValidationException("product.pricePositive");
        }
        if (request.getPurchasePrice() == null || request.getPurchasePrice().signum() < 0) {
            throw new ValidationException("product.purchasePriceNegative");
        }
        if (request.getTaxRate() == null || !(request.getTaxRate().compareTo(BigDecimal.ZERO) == 0 || request.getTaxRate().compareTo(BigDecimal.valueOf(20)) == 0)) {
            throw new ValidationException("product.invalidTax");
        }
        normalizeUnit(request.getUnit());
    }

    private String normalizeUnit(String unit) {
        String normalized = unit == null || unit.isBlank() ? "pcs" : unit.trim().toLowerCase();
        if (!normalized.equals("pcs") && !normalized.equals("kg")) {
            throw new ValidationException("product.invalidUnit");
        }
        return normalized;
    }
}

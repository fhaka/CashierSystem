package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.dto.ProductRequest;
import com.supermarket.exception.ProductNotFoundException;
import com.supermarket.model.Cashier;
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
import java.util.Optional;
import java.util.TreeMap;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final FilterUtil<Product> productFilter = new FilterUtil<>();

    private final AuditService auditService;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository, AuditService auditService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
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

    public Optional<Product> findOptionalByBarcode(String barcode) {
        return productRepository.findByBarcode(barcode);
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
    public Product create(ProductRequest request, Cashier actor) {
        validateProductRequest(request);
        requireFreeBarcode(request.getBarcode().trim(), null);
        Category category = resolveCategory(request);
        String unit = normalizeUnit(request.getUnit());
        Product product = new Product(request.getName().trim(), request.getBarcode().trim(), request.getPrice(), request.getPurchasePrice(),
                request.getTaxRate(), Quantities.requireStock(request.getStock(), unit), unit, category);
        applyReorderSettings(product, request, unit);
        Product saved = productRepository.save(product);
        auditService.record(actor, "PRODUCT_CREATED", "PRODUCT", saved.getId(),
                saved.getName() + ", price " + saved.getPrice() + ", stock " + saved.getStock().stripTrailingZeros().toPlainString());
        return saved;
    }

    @Transactional
    public Product update(Long id, ProductRequest request, Cashier actor) {
        validateProductRequest(request);
        Product product = findById(id);
        String before = summary(product);
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
        applyReorderSettings(product, request, unit);
        Product saved = productRepository.save(product);
        String after = summary(saved);
        if (!before.equals(after)) {
            auditService.record(actor, "PRODUCT_UPDATED", "PRODUCT", saved.getId(), before + " -> " + after);
        }
        return saved;
    }

    /**
     * Products are never deleted: old sales and purchases keep pointing at them. A deactivated product
     * disappears from the till and can be reactivated later.
     */
    @Transactional
    public void deactivate(Long id, Cashier actor) {
        Product product = findById(id);
        product.setActive(false);
        auditService.record(actor, "PRODUCT_DEACTIVATED", "PRODUCT", id, product.getName());
    }

    @Transactional
    public Product activate(Long id, Cashier actor) {
        Product product = findById(id);
        product.setActive(true);
        auditService.record(actor, "PRODUCT_ACTIVATED", "PRODUCT", id, product.getName());
        return product;
    }

    private static void applyReorderSettings(Product product, ProductRequest request, String unit) {
        product.setMinStock(request.getMinStock() == null ? null : Quantities.requireStock(request.getMinStock(), unit));
        product.setReorderQuantity(request.getReorderQuantity() == null || request.getReorderQuantity().signum() == 0
                ? null : Quantities.requirePositive(request.getReorderQuantity(), unit));
    }

    /** The fields worth auditing, in one line, to show what an edit changed. */
    private static String summary(Product product) {
        return product.getName() + " [" + product.getBarcode() + "] price " + product.getPrice()
                + ", cost " + product.getPurchasePrice() + ", VAT " + product.getTaxRate().stripTrailingZeros().toPlainString()
                + "%, stock " + product.getStock().stripTrailingZeros().toPlainString() + " " + product.getUnit()
                + (product.getMinStock() == null ? "" : ", min " + product.getMinStock().stripTrailingZeros().toPlainString());
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

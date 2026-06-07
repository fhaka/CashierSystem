package com.supermarket.service;

import com.supermarket.dto.PurchaseInvoiceRequest;
import com.supermarket.dto.PurchaseItemRequest;
import com.supermarket.model.Product;
import com.supermarket.model.PurchaseInvoice;
import com.supermarket.model.PurchaseItem;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.PurchaseInvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PurchaseInvoiceService {

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    public PurchaseInvoiceService(PurchaseInvoiceRepository purchaseInvoiceRepository, ProductRepository productRepository, ProductService productService) {
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.productRepository = productRepository;
        this.productService = productService;
    }

    public List<PurchaseInvoice> findAll() {
        return purchaseInvoiceRepository.findAll();
    }

    @Transactional
    public PurchaseInvoice create(PurchaseInvoiceRequest request) {
        validateRequest(request);
        BigDecimal total = BigDecimal.ZERO;
        PurchaseInvoice invoice = new PurchaseInvoice(
                request.getInvoiceNumber().trim(),
                request.getCompany().trim(),
                request.getInvoiceDate(),
                BigDecimal.ZERO
        );

        for (PurchaseItemRequest itemRequest : request.getItems()) {
            validateItem(itemRequest);
            Product product = productService.findById(itemRequest.getProductId());
            String unit = normalizeUnit(itemRequest.getUnit());
            BigDecimal lineTotal = itemRequest.getPurchasePrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            total = total.add(lineTotal);

            product.setPurchasePrice(itemRequest.getPurchasePrice());
            product.setPrice(itemRequest.getSellingPrice());
            product.setTaxRate(itemRequest.getTaxRate());
            product.setUnit(unit);
            product.setStock(product.getStock() + itemRequest.getQuantity());
            productRepository.save(product);

            invoice.addItem(new PurchaseItem(
                    product,
                    itemRequest.getQuantity(),
                    itemRequest.getPurchasePrice(),
                    itemRequest.getSellingPrice(),
                    itemRequest.getTaxRate(),
                    unit,
                    lineTotal
            ));
        }

        invoice.setTotalAmount(total);
        return purchaseInvoiceRepository.save(invoice);
    }

    private void validateRequest(PurchaseInvoiceRequest request) {
        if (request.getInvoiceNumber() == null || request.getInvoiceNumber().isBlank()) {
            throw new IllegalArgumentException("Purchase invoice number is required");
        }
        if (purchaseInvoiceRepository.existsByInvoiceNumberIgnoreCase(request.getInvoiceNumber().trim())) {
            throw new IllegalArgumentException("Purchase invoice number already exists");
        }
        if (request.getCompany() == null || request.getCompany().isBlank()) {
            throw new IllegalArgumentException("Company is required");
        }
        if (request.getInvoiceDate() == null) {
            throw new IllegalArgumentException("Invoice date is required");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Purchase invoice needs at least one product");
        }
    }

    private void validateItem(PurchaseItemRequest item) {
        if (item.getProductId() == null) {
            throw new IllegalArgumentException("Product is required");
        }
        if (item.getQuantity() == null || item.getQuantity() <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        if (item.getPurchasePrice() == null || item.getPurchasePrice().signum() < 0) {
            throw new IllegalArgumentException("Purchase price cannot be negative");
        }
        if (item.getSellingPrice() == null || item.getSellingPrice().signum() <= 0) {
            throw new IllegalArgumentException("Selling price must be greater than zero");
        }
        if (item.getTaxRate() == null || !(item.getTaxRate().compareTo(BigDecimal.ZERO) == 0 || item.getTaxRate().compareTo(BigDecimal.valueOf(20)) == 0)) {
            throw new IllegalArgumentException("Tax must be either 0% or 20%");
        }
    }

    private String normalizeUnit(String unit) {
        String normalized = unit == null || unit.isBlank() ? "pcs" : unit.trim().toLowerCase();
        if (!normalized.equals("pcs") && !normalized.equals("kg")) {
            throw new IllegalArgumentException("Product unit must be either pcs or kg");
        }
        return normalized;
    }
}

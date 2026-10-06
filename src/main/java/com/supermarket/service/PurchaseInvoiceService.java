package com.supermarket.service;

import com.supermarket.exception.ValidationException;
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
            throw new ValidationException("purchase.invoiceNumberRequired");
        }
        if (purchaseInvoiceRepository.existsByInvoiceNumberIgnoreCase(request.getInvoiceNumber().trim())) {
            throw new ValidationException("purchase.invoiceNumberExists");
        }
        if (request.getCompany() == null || request.getCompany().isBlank()) {
            throw new ValidationException("purchase.companyRequired");
        }
        if (request.getInvoiceDate() == null) {
            throw new ValidationException("purchase.dateRequired");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ValidationException("purchase.itemsRequired");
        }
    }

    private void validateItem(PurchaseItemRequest item) {
        if (item.getProductId() == null) {
            throw new ValidationException("purchase.productRequired");
        }
        if (item.getQuantity() == null || item.getQuantity() <= 0) {
            throw new ValidationException("cart.quantityPositive");
        }
        if (item.getPurchasePrice() == null || item.getPurchasePrice().signum() < 0) {
            throw new ValidationException("product.purchasePriceNegative");
        }
        if (item.getSellingPrice() == null || item.getSellingPrice().signum() <= 0) {
            throw new ValidationException("purchase.sellingPricePositive");
        }
        if (item.getTaxRate() == null || !(item.getTaxRate().compareTo(BigDecimal.ZERO) == 0 || item.getTaxRate().compareTo(BigDecimal.valueOf(20)) == 0)) {
            throw new ValidationException("product.invalidTax");
        }
    }

    private String normalizeUnit(String unit) {
        String normalized = unit == null || unit.isBlank() ? "pcs" : unit.trim().toLowerCase();
        if (!normalized.equals("pcs") && !normalized.equals("kg")) {
            throw new ValidationException("product.invalidUnit");
        }
        return normalized;
    }
}

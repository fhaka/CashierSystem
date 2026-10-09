package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.dto.PurchaseInvoiceRequest;
import com.supermarket.dto.PurchaseItemRequest;
import com.supermarket.model.Cashier;
import com.supermarket.model.PriceChange;
import com.supermarket.model.ProductPackage;
import com.supermarket.model.Product;
import com.supermarket.model.PurchaseInvoice;
import com.supermarket.model.PurchaseItem;
import com.supermarket.model.Supplier;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.PurchaseInvoiceRepository;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.util.Optional;
import java.util.Map;
import java.math.RoundingMode;
import java.util.List;

@Service
public class PurchaseInvoiceService {

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final AuditService auditService;
    private final SupplierService supplierService;

    private final PackageService packageService;
    private final PriceHistoryService priceHistoryService;

    public PurchaseInvoiceService(PurchaseInvoiceRepository purchaseInvoiceRepository, ProductRepository productRepository, ProductService productService,
                                  AuditService auditService, SupplierService supplierService, PackageService packageService,
                                  PriceHistoryService priceHistoryService) {
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.productRepository = productRepository;
        this.productService = productService;
        this.auditService = auditService;
        this.supplierService = supplierService;
        this.packageService = packageService;
        this.priceHistoryService = priceHistoryService;
    }

    public List<PurchaseInvoice> findAll() {
        return purchaseInvoiceRepository.findAll();
    }

    @Transactional
    public PurchaseInvoice create(PurchaseInvoiceRequest request, Cashier actor) {
        validateRequest(request);
        Supplier supplier = supplierService.resolveForPurchase(request.getSupplierId(), request.getCompany(), actor);
        BigDecimal total = BigDecimal.ZERO;
        PurchaseInvoice invoice = new PurchaseInvoice(
                request.getInvoiceNumber().trim(),
                supplier.getName(),
                request.getInvoiceDate(),
                BigDecimal.ZERO
        );
        invoice.setSupplier(supplier);

        // Lock the products like a sale does, so a sale at the same moment cannot overwrite the new stock.
        request.getItems().forEach(this::validateItem);
        Map<Long, Product> products = productRepository.findAllForUpdate(
                        request.getItems().stream().map(PurchaseItemRequest::getProductId).distinct().sorted().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        for (PurchaseItemRequest itemRequest : request.getItems()) {
            Product product = Optional.ofNullable(products.get(itemRequest.getProductId()))
                    .orElseGet(() -> productService.findById(itemRequest.getProductId()));
            ProductPackage box = itemRequest.getPackageId() == null ? null : packageService.find(itemRequest.getPackageId());
            if (box != null && !box.getProductId().equals(product.getId())) {
                throw new ValidationException("package.notFound", itemRequest.getPackageId());
            }
            String unit = box == null ? normalizeUnit(itemRequest.getUnit()) : Quantities.PIECES;
            BigDecimal counted = Quantities.requirePositive(itemRequest.getQuantity(), unit);
            BigDecimal lineTotal = itemRequest.getPurchasePrice().multiply(counted).setScale(2, RoundingMode.HALF_UP);
            // Bought in boxes: stock and cost are per piece (box price shared over its pieces).
            BigDecimal quantity = box == null ? counted : counted.multiply(BigDecimal.valueOf(box.getPieces()));
            BigDecimal piecePrice = box == null ? itemRequest.getPurchasePrice()
                    : itemRequest.getPurchasePrice().divide(BigDecimal.valueOf(box.getPieces()), 2, RoundingMode.HALF_UP);
            total = total.add(lineTotal);

            priceHistoryService.record(product.getId(), null, PriceChange.Source.PURCHASE, actor,
                    product.getPrice(), itemRequest.getSellingPrice(), product.getPurchasePrice(), piecePrice);
            product.setPurchasePrice(piecePrice);
            product.setPrice(itemRequest.getSellingPrice());
            product.setTaxRate(itemRequest.getTaxRate());
            product.setUnit(unit);
            product.setStock(product.getStock().add(quantity));

            PurchaseItem item = new PurchaseItem(
                    product,
                    quantity,
                    piecePrice,
                    itemRequest.getSellingPrice(),
                    itemRequest.getTaxRate(),
                    unit,
                    lineTotal
            );
            item.setExpiryDate(itemRequest.getExpiryDate());
            if (box != null) {
                item.setPackage(box.getId(), counted);
            }
            invoice.addItem(item);
        }

        invoice.setTotalAmount(total);
        PurchaseInvoice saved = purchaseInvoiceRepository.save(invoice);
        auditService.record(actor, "PURCHASE_SAVED", "PURCHASE_INVOICE", saved.getInvoiceNumber(),
                saved.getCompany() + ", " + saved.getItems().size() + " lines, " + saved.getTotalAmount() + " LEK");
        return saved;
    }

    private void validateRequest(PurchaseInvoiceRequest request) {
        if (request.getInvoiceNumber() == null || request.getInvoiceNumber().isBlank()) {
            throw new ValidationException("purchase.invoiceNumberRequired");
        }
        if (purchaseInvoiceRepository.existsByInvoiceNumberIgnoreCase(request.getInvoiceNumber().trim())) {
            throw new ValidationException("purchase.invoiceNumberExists");
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

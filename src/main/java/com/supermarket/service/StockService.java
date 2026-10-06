package com.supermarket.service;

import com.supermarket.dto.InventoryDtos.AdjustmentRequest;
import com.supermarket.dto.InventoryDtos.ExpiringLine;
import com.supermarket.dto.InventoryDtos.ReorderGroup;
import com.supermarket.dto.InventoryDtos.ReorderLine;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.model.PurchaseItem;
import com.supermarket.model.StockAdjustment;
import com.supermarket.model.Supplier;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.PurchaseItemRepository;
import com.supermarket.repository.StockAdjustmentRepository;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stock outside of selling: manual adjustments, low-stock and reorder lists, and expiry dates. */
@Service
public class StockService {

    private final ProductRepository productRepository;
    private final StockAdjustmentRepository adjustmentRepository;
    private final PurchaseItemRepository purchaseItemRepository;
    private final AuditService auditService;

    public StockService(ProductRepository productRepository, StockAdjustmentRepository adjustmentRepository,
                        PurchaseItemRepository purchaseItemRepository, AuditService auditService) {
        this.productRepository = productRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.purchaseItemRepository = purchaseItemRepository;
        this.auditService = auditService;
    }

    @Transactional
    public StockAdjustment adjust(AdjustmentRequest request, Cashier actor) {
        StockAdjustment.Reason reason = parseReason(request.reason());
        if (reason == StockAdjustment.Reason.COUNT_CORRECTION) {
            throw new ValidationException("stock.countReasonReserved");
        }
        if (request.quantityChange() == null || request.quantityChange().signum() == 0) {
            throw new ValidationException("stock.changeRequired");
        }
        if (reason == StockAdjustment.Reason.OTHER && (request.note() == null || request.note().isBlank())) {
            throw new ValidationException("stock.noteRequired");
        }
        Product product = lock(request.productId());
        BigDecimal change = Quantities.requirePositive(request.quantityChange().abs(), product.getUnit());
        if (request.quantityChange().signum() < 0) {
            change = change.negate();
        }
        return apply(product, change, reason, request.note(), null, actor);
    }

    /** Changes the stock of a locked product and records why. Used by adjustments and stock counts. */
    StockAdjustment apply(Product product, BigDecimal change, StockAdjustment.Reason reason, String note, Long countId, Cashier actor) {
        BigDecimal newStock = product.getStock().add(change);
        if (newStock.signum() < 0) {
            throw new ValidationException("stock.wouldBeNegative", product.getName(), product.getStock().stripTrailingZeros().toPlainString());
        }
        product.setStock(newStock);
        StockAdjustment adjustment = adjustmentRepository.save(new StockAdjustment(product, actor, change, reason,
                note == null || note.isBlank() ? null : note.trim(), countId, LocalDateTime.now()));
        if (countId == null) {
            auditService.record(actor, "STOCK_ADJUSTED", "PRODUCT", product.getId(), product.getName() + ": "
                    + (change.signum() > 0 ? "+" : "") + change.stripTrailingZeros().toPlainString() + " " + reason
                    + (adjustment.getNote() == null ? "" : " - " + adjustment.getNote()));
        }
        return adjustment;
    }

    Product lock(Long productId) {
        if (productId == null) {
            throw new ValidationException("purchase.productRequired");
        }
        return productRepository.findAllForUpdate(List.of(productId)).stream().findFirst()
                .orElseThrow(() -> new ValidationException("product.notFound", productId));
    }

    @Transactional(readOnly = true)
    public List<StockAdjustment> history(Long productId) {
        return adjustmentRepository.findForProduct(productId);
    }

    @Transactional(readOnly = true)
    public List<Product> lowStock() {
        return productRepository.findByActiveTrue().stream()
                .filter(Product::isLowStock)
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Low-stock products grouped by the supplier they were last bought from, with a suggested quantity. */
    @Transactional(readOnly = true)
    public List<ReorderGroup> reorderList() {
        List<Product> low = lowStock();
        Map<Long, PurchaseItem> lastDelivery = new LinkedHashMap<>();
        if (!low.isEmpty()) {
            for (PurchaseItem item : purchaseItemRepository.findLatestForProducts(low.stream().map(Product::getId).toList())) {
                lastDelivery.putIfAbsent(item.getProduct().getId(), item);
            }
        }
        Map<Long, List<ReorderLine>> bySupplier = new LinkedHashMap<>();
        Map<Long, String> supplierNames = new LinkedHashMap<>();
        for (Product product : low) {
            PurchaseItem last = lastDelivery.get(product.getId());
            Supplier supplier = last == null ? null : last.getInvoice().getSupplier();
            Long key = supplier == null ? null : supplier.getId();
            supplierNames.put(key, supplier == null ? null : supplier.getName());
            BigDecimal suggested = suggestedQuantity(product);
            BigDecimal cost = last == null ? product.getPurchasePrice() : last.getPurchasePrice();
            bySupplier.computeIfAbsent(key, k -> new ArrayList<>()).add(new ReorderLine(product.getId(), product.getName(),
                    product.getBarcode(), product.getUnit(), product.getStock(), product.getMinStock(), suggested, cost,
                    cost.multiply(suggested).setScale(2, RoundingMode.HALF_UP)));
        }
        List<ReorderGroup> groups = new ArrayList<>();
        bySupplier.forEach((supplierId, lines) -> groups.add(new ReorderGroup(supplierId, supplierNames.get(supplierId), lines,
                lines.stream().map(ReorderLine::estimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add))));
        // Known suppliers first, alphabetically; products never bought from anyone last.
        groups.sort(Comparator.comparing((ReorderGroup g) -> g.supplierName() == null)
                .thenComparing(g -> g.supplierName() == null ? "" : g.supplierName(), String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    /** Reorder quantity if set, otherwise enough to reach twice the minimum. Pieces are rounded up. */
    static BigDecimal suggestedQuantity(Product product) {
        BigDecimal quantity = product.getReorderQuantity() != null
                ? product.getReorderQuantity()
                : product.getMinStock().multiply(BigDecimal.valueOf(2)).subtract(product.getStock());
        if (quantity.signum() <= 0) {
            quantity = product.getMinStock().signum() > 0 ? product.getMinStock() : BigDecimal.ONE;
        }
        return Quantities.KILOGRAMS.equals(product.getUnit())
                ? quantity.setScale(3, RoundingMode.HALF_UP)
                : quantity.setScale(0, RoundingMode.CEILING);
    }

    /**
     * Deliveries probably still on the shelf that expire within the given days (or already expired).
     * The shop does not record which delivery each sale came from, so it is estimated: the stock left is
     * assumed to be the most recent deliveries, because older goods are sold first.
     */
    @Transactional(readOnly = true)
    public List<ExpiringLine> expiring(int days) {
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(Math.max(0, days));
        Map<Long, BigDecimal> stockLeft = new LinkedHashMap<>();
        List<ExpiringLine> lines = new ArrayList<>();
        for (PurchaseItem delivery : purchaseItemRepository.findWithExpiryForProductsInStock()) {
            Product product = delivery.getProduct();
            BigDecimal left = stockLeft.computeIfAbsent(product.getId(), id -> product.getStock());
            BigDecimal onShelf = left.min(delivery.getQuantity());
            stockLeft.put(product.getId(), left.subtract(onShelf));
            if (onShelf.signum() > 0 && !delivery.getExpiryDate().isAfter(limit)) {
                Supplier supplier = delivery.getInvoice().getSupplier();
                lines.add(new ExpiringLine(product.getId(), product.getName(), product.getBarcode(), product.getUnit(),
                        delivery.getExpiryDate(), ChronoUnit.DAYS.between(today, delivery.getExpiryDate()), onShelf,
                        supplier == null ? delivery.getInvoice().getCompany() : supplier.getName(),
                        delivery.getInvoice().getInvoiceNumber()));
            }
        }
        lines.sort(Comparator.comparing(ExpiringLine::expiryDate).thenComparing(ExpiringLine::productName));
        return lines;
    }

    static StockAdjustment.Reason parseReason(String reason) {
        try {
            return StockAdjustment.Reason.valueOf(reason == null ? "" : reason.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("stock.invalidReason");
        }
    }
}

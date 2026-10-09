package com.supermarket.dto;

import com.supermarket.model.AuditEvent;
import com.supermarket.model.PriceChange;
import com.supermarket.model.Product;
import com.supermarket.model.ProductPackage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the "Magazina" (warehouse) page shows. Stock is always in pieces (or kg); boxes are shown next to it. */
public final class Warehouse {

    private Warehouse() {
    }

    /** The figures at the top of the page. Values in LEK. */
    public record Overview(long activeProducts, BigDecimal stockValueAtCost, BigDecimal stockValueAtPrice, long lowStock,
                           long outOfStock, long deadStock, long pricesChangedToday) {
    }

    /** One row of the product list. */
    public record ProductRow(Long id, String name, String barcode, String category, String unit, BigDecimal stock,
                             BigDecimal minStock, BigDecimal price, BigDecimal purchasePrice, BigDecimal taxRate,
                             BigDecimal marginPercent, BigDecimal stockValue, boolean active, int packages,
                             String biggestPackage, Integer biggestPackagePieces, BigDecimal soldLast30Days,
                             LocalDateTime lastSaleAt) {
    }

    /** One stock movement: what changed the stock, when, and by how much (negative = out). */
    public record Movement(LocalDateTime at, String type, String reference, BigDecimal change, BigDecimal stockAfter,
                           String detail, String cashier) {
    }

    /** A supplier of the product: last purchase and price per piece. */
    public record SupplierRow(String supplier, LocalDate lastPurchase, BigDecimal lastPurchasePrice, BigDecimal totalQuantity,
                              int purchases) {
    }

    /** Quantity sold in one week (Monday). */
    public record WeekSales(LocalDate week, BigDecimal quantity, BigDecimal revenue) {
    }

    public record SalesStats(BigDecimal soldLast30Days, BigDecimal revenueLast30Days, LocalDateTime lastSaleAt,
                             BigDecimal daysOfStockLeft, List<WeekSales> weeks) {
    }

    /** Everything on a product's card. */
    public record ProductCard(Product product, String category, BigDecimal marginPercent, BigDecimal unitPrice,
                              String unitPriceLabel, List<ProductPackage> packages, List<PriceChange> priceHistory,
                              List<Movement> movements, List<SupplierRow> suppliers, SalesStats sales,
                              List<AuditEvent> history) {
    }

    /** Raise or lower selling prices by a percentage, rounded to roundTo LEK (0 = to the cent). */
    public record BulkPriceRequest(List<Long> productIds, BigDecimal percent, BigDecimal roundTo, boolean apply) {
    }

    public record BulkPriceRow(Long productId, String name, BigDecimal oldPrice, BigDecimal newPrice) {
    }

    /** A shelf label to print: one product, or one of its boxes. */
    public record LabelItem(Long productId, Long packageId, Integer copies) {
    }

    public record LabelRequest(List<LabelItem> items, String printerName) {
    }

    /** What is printed on a shelf label. unitPrice is per kg or litre (or per piece for a box), when it makes sense. */
    public record Label(String name, String packageText, BigDecimal price, String unitPriceText, String barcode, int copies) {
    }
}

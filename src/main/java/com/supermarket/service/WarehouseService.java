package com.supermarket.service;

import com.supermarket.dto.Warehouse;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.PriceChange;
import com.supermarket.model.Product;
import com.supermarket.model.ProductPackage;
import com.supermarket.repository.AuditEventRepository;
import com.supermarket.repository.ProductPackageRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.util.Quantities;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The "Magazina" page: stock figures, the product list with sales and margins, a product's full card, bulk price
 * changes and shelf labels. Read-only except for bulk prices.
 */
@Service
public class WarehouseService {

    private static final int DEAD_STOCK_DAYS = 60;
    private static final int MOVEMENTS_SHOWN = 150;

    private final EntityManager entityManager;
    private final ProductRepository productRepository;
    private final ProductPackageRepository packageRepository;
    private final PriceHistoryService priceHistoryService;
    private final AuditEventRepository auditEventRepository;
    private final AuditService auditService;
    private final MessageSource messageSource;
    private final boolean costIncludesVat;

    public WarehouseService(EntityManager entityManager, ProductRepository productRepository,
                            ProductPackageRepository packageRepository, PriceHistoryService priceHistoryService,
                            AuditEventRepository auditEventRepository, AuditService auditService, MessageSource messageSource,
                            @Value("${pos.reports.cost-includes-vat:true}") boolean costIncludesVat) {
        this.entityManager = entityManager;
        this.productRepository = productRepository;
        this.packageRepository = packageRepository;
        this.priceHistoryService = priceHistoryService;
        this.auditEventRepository = auditEventRepository;
        this.auditService = auditService;
        this.messageSource = messageSource;
        this.costIncludesVat = costIncludesVat;
    }

    @Transactional(readOnly = true)
    public Warehouse.Overview overview() {
        List<Product> active = productRepository.findByActiveTrue();
        Map<Long, LocalDateTime> lastSale = lastSaleByProduct();
        LocalDateTime deadSince = LocalDateTime.now().minusDays(DEAD_STOCK_DAYS);
        BigDecimal atCost = BigDecimal.ZERO;
        BigDecimal atPrice = BigDecimal.ZERO;
        long low = 0;
        long out = 0;
        long dead = 0;
        for (Product p : active) {
            if (p.getStock().signum() > 0) {
                atCost = atCost.add(p.getStock().multiply(p.getPurchasePrice()));
                atPrice = atPrice.add(p.getStock().multiply(p.getPrice()));
                LocalDateTime last = lastSale.get(p.getId());
                if (last == null || last.isBefore(deadSince)) {
                    dead++;
                }
            } else {
                out++;
            }
            if (p.getMinStock() != null && p.getStock().compareTo(p.getMinStock()) <= 0) {
                low++;
            }
        }
        long changedToday = priceHistoryService.productsWithNewPriceSince(LocalDate.now().atStartOfDay()).size();
        return new Warehouse.Overview(active.size(), money(atCost), money(atPrice), low, out, dead, changedToday);
    }

    /** Every product (active or not) with its stock, margin, boxes and recent sales. */
    @Transactional(readOnly = true)
    public List<Warehouse.ProductRow> products() {
        Map<Long, LocalDateTime> lastSale = lastSaleByProduct();
        Map<Long, BigDecimal> sold30 = new HashMap<>();
        for (Object[] r : entityManager.createQuery("""
                        select si.product.id, sum(si.quantity) from SaleItem si join si.sale s
                        where s.date >= :since group by si.product.id
                        """, Object[].class)
                .setParameter("since", LocalDate.now().minusDays(30).atStartOfDay()).getResultList()) {
            sold30.put((Long) r[0], (BigDecimal) r[1]);
        }
        Map<Long, List<ProductPackage>> boxes = new HashMap<>();
        packageRepository.findAllActive().forEach(box -> boxes.computeIfAbsent(box.getProductId(), id -> new ArrayList<>()).add(box));

        List<Warehouse.ProductRow> rows = new ArrayList<>();
        for (Product p : entityManager.createQuery("select p from Product p left join fetch p.category order by p.name", Product.class)
                .getResultList()) {
            List<ProductPackage> own = boxes.getOrDefault(p.getId(), List.of());
            ProductPackage biggest = own.stream().max(Comparator.comparingInt(ProductPackage::getPieces)).orElse(null);
            rows.add(new Warehouse.ProductRow(p.getId(), p.getName(), p.getBarcode(),
                    p.getCategory() == null ? null : p.getCategory().getName(), p.getUnit(), p.getStock(), p.getMinStock(),
                    p.getPrice(), p.getPurchasePrice(), p.getTaxRate(), margin(p), money(p.getStock().max(BigDecimal.ZERO).multiply(p.getPurchasePrice())),
                    p.isActive(), own.size(), biggest == null ? null : biggest.getName(), biggest == null ? null : biggest.getPieces(),
                    sold30.getOrDefault(p.getId(), BigDecimal.ZERO), lastSale.get(p.getId())));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Warehouse.ProductCard card(Long productId) {
        Product product = entityManager.createQuery("select p from Product p left join fetch p.category where p.id = :id", Product.class)
                .setParameter("id", productId).getResultStream().findFirst()
                .orElseThrow(() -> new ValidationException("product.notFound", productId));
        UnitPrice unitPrice = unitPrice(product, product.getPrice(), 1);
        return new Warehouse.ProductCard(product, product.getCategory() == null ? null : product.getCategory().getName(),
                margin(product), unitPrice == null ? null : unitPrice.amount(), unitPrice == null ? null : unitPrice.label(),
                packageRepository.findForProduct(productId), priceHistoryService.forProduct(productId), movements(product),
                suppliers(productId), sales(product),
                auditEventRepository.findForEntity("PRODUCT", String.valueOf(productId), PageRequest.of(0, 50)));
    }

    /** Shows (apply=false) or applies new selling prices: +/- percent, rounded to roundTo LEK. */
    @Transactional
    public List<Warehouse.BulkPriceRow> bulkPrices(Warehouse.BulkPriceRequest request, Cashier actor) {
        if (request.productIds() == null || request.productIds().isEmpty()) {
            throw new ValidationException("warehouse.noProducts");
        }
        if (request.percent() == null || request.percent().compareTo(BigDecimal.valueOf(-90)) < 0
                || request.percent().compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new ValidationException("warehouse.invalidPercent");
        }
        BigDecimal roundTo = request.roundTo() == null ? BigDecimal.ZERO : request.roundTo();
        List<Product> products = request.apply()
                ? productRepository.findAllForUpdate(request.productIds().stream().distinct().sorted().toList())
                : productRepository.findAllById(request.productIds());
        List<Warehouse.BulkPriceRow> rows = new ArrayList<>();
        for (Product p : products) {
            BigDecimal newPrice = round(p.getPrice().multiply(BigDecimal.ONE.add(request.percent().movePointLeft(2))), roundTo);
            if (newPrice.signum() <= 0) {
                newPrice = p.getPrice();
            }
            rows.add(new Warehouse.BulkPriceRow(p.getId(), p.getName(), p.getPrice(), newPrice));
            if (request.apply() && newPrice.compareTo(p.getPrice()) != 0) {
                priceHistoryService.record(p.getId(), null, PriceChange.Source.BULK, actor, p.getPrice(), newPrice, null, null);
                p.setPrice(newPrice);
            }
        }
        if (request.apply()) {
            auditService.record(actor, "PRICE_BULK", "PRODUCT", null, products.size() + " products, "
                    + request.percent().stripTrailingZeros().toPlainString() + "%, rounded to " + roundTo.stripTrailingZeros().toPlainString());
        }
        rows.sort(Comparator.comparing(Warehouse.BulkPriceRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** The shelf labels to print, in the order asked, with the current prices. */
    @Transactional(readOnly = true)
    public List<Warehouse.Label> labels(List<Warehouse.LabelItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("warehouse.noProducts");
        }
        List<Warehouse.Label> labels = new ArrayList<>();
        for (Warehouse.LabelItem item : items) {
            int copies = item.copies() == null ? 1 : Math.max(1, Math.min(50, item.copies()));
            if (item.packageId() != null) {
                ProductPackage box = packageRepository.findById(item.packageId())
                        .orElseThrow(() -> new ValidationException("package.notFound", item.packageId()));
                Product p = box.getProduct();
                BigDecimal price = box.effectivePrice();
                UnitPrice perUnit = unitPrice(p, price, box.getPieces());
                String perPiece = text("label.perPiece", labelMoney(price.divide(BigDecimal.valueOf(box.getPieces()), 2, RoundingMode.HALF_UP)));
                labels.add(new Warehouse.Label(p.getName(), box.getName(), price,
                        perUnit == null ? perPiece : perPiece + " | " + perUnit.text(), box.getBarcode(), copies));
            } else {
                Product p = productRepository.findById(item.productId())
                        .orElseThrow(() -> new ValidationException("product.notFound", item.productId()));
                UnitPrice perUnit = unitPrice(p, p.getPrice(), 1);
                labels.add(new Warehouse.Label(p.getName(), null, p.getPrice(), perUnit == null ? null : perUnit.text(), p.getBarcode(), copies));
            }
        }
        return labels;
    }

    /** Products whose selling price changed since the start of today: their shelf labels need reprinting. */
    @Transactional(readOnly = true)
    public List<Long> productsWithNewPriceToday() {
        return priceHistoryService.productsWithNewPriceSince(LocalDate.now().atStartOfDay());
    }

    // --- product card parts -------------------------------------------------------------------------------------

    /** Sales, refunds, purchases and adjustments, newest first, with the stock after each one. */
    private List<Warehouse.Movement> movements(Product product) {
        Long id = product.getId();
        List<Warehouse.Movement> all = new ArrayList<>();
        for (Object[] r : list("""
                select s.date, s.invoiceNumber, si.quantity, si.packageName, si.packageCount, c.fullName
                from SaleItem si join si.sale s left join s.cashier c where si.product.id = :id order by s.date desc
                """, id)) {
            String detail = r[3] == null ? null : ((BigDecimal) r[4]).stripTrailingZeros().toPlainString() + " x " + r[3];
            all.add(new Warehouse.Movement((LocalDateTime) r[0], "SALE", (String) r[1], ((BigDecimal) r[2]).negate(), null, detail, (String) r[5]));
        }
        for (Object[] r : list("""
                select rf.createdAt, rf.refundNumber, ri.quantity, rf.reason, c.fullName
                from RefundItem ri join ri.refund rf join ri.saleItem si left join rf.cashier c where si.product.id = :id
                order by rf.createdAt desc
                """, id)) {
            all.add(new Warehouse.Movement((LocalDateTime) r[0], "REFUND", (String) r[1], (BigDecimal) r[2], null, (String) r[3], (String) r[4]));
        }
        for (Object[] r : list("""
                select inv.invoiceDate, inv.invoiceNumber, pi.quantity, inv.company, pi.packageCount, pi.id
                from PurchaseItem pi join pi.invoice inv where pi.product.id = :id order by inv.invoiceDate desc, pi.id desc
                """, id)) {
            String detail = (String) r[3] + (r[4] == null ? "" : " (" + ((BigDecimal) r[4]).stripTrailingZeros().toPlainString() + " " + text("warehouse.boxes") + ")");
            all.add(new Warehouse.Movement(((LocalDate) r[0]).atStartOfDay(), "PURCHASE", (String) r[1], (BigDecimal) r[2], null, detail, null));
        }
        for (Object[] r : list("""
                select a.createdAt, a.reason, a.quantityChange, a.note, c.fullName
                from StockAdjustment a left join a.cashier c where a.product.id = :id order by a.createdAt desc
                """, id)) {
            all.add(new Warehouse.Movement((LocalDateTime) r[0], "ADJUSTMENT", String.valueOf(r[1]), (BigDecimal) r[2], null,
                    (String) r[3], (String) r[4]));
        }
        all.sort(Comparator.comparing(Warehouse.Movement::at).reversed());
        // Walk back from today's stock: the stock after each movement.
        List<Warehouse.Movement> shown = new ArrayList<>();
        BigDecimal stock = product.getStock();
        for (Warehouse.Movement m : all.subList(0, Math.min(MOVEMENTS_SHOWN, all.size()))) {
            shown.add(new Warehouse.Movement(m.at(), m.type(), m.reference(), m.change(), stock, m.detail(), m.cashier()));
            stock = stock.subtract(m.change());
        }
        return shown;
    }

    private List<Warehouse.SupplierRow> suppliers(Long productId) {
        Map<String, Warehouse.SupplierRow> bySupplier = new LinkedHashMap<>();
        for (Object[] r : list("""
                select coalesce(sup.name, inv.company), inv.invoiceDate, pi.purchasePrice, pi.quantity
                from PurchaseItem pi join pi.invoice inv left join inv.supplier sup where pi.product.id = :id
                order by inv.invoiceDate desc, pi.id desc
                """, productId)) {
            String name = (String) r[0];
            Warehouse.SupplierRow row = bySupplier.get(name);
            bySupplier.put(name, row == null
                    ? new Warehouse.SupplierRow(name, (LocalDate) r[1], (BigDecimal) r[2], (BigDecimal) r[3], 1)
                    : new Warehouse.SupplierRow(name, row.lastPurchase(), row.lastPurchasePrice(), row.totalQuantity().add((BigDecimal) r[3]),
                    row.purchases() + 1));
        }
        return List.copyOf(bySupplier.values());
    }

    private Warehouse.SalesStats sales(Product product) {
        LocalDate thisWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = thisWeek.minusWeeks(7);
        Map<LocalDate, BigDecimal[]> weeks = new TreeMap<>();
        for (int i = 0; i < 8; i++) {
            weeks.put(from.plusWeeks(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        BigDecimal sold30 = BigDecimal.ZERO;
        BigDecimal revenue30 = BigDecimal.ZERO;
        LocalDateTime since30 = LocalDate.now().minusDays(30).atStartOfDay();
        for (Object[] r : entityManager.createQuery("""
                        select s.date, si.quantity, si.lineTotal from SaleItem si join si.sale s
                        where si.product.id = :id and s.date >= :from
                        """, Object[].class)
                .setParameter("id", product.getId()).setParameter("from", from.atStartOfDay()).getResultList()) {
            LocalDateTime date = (LocalDateTime) r[0];
            BigDecimal[] week = weeks.get(date.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            if (week != null) {
                week[0] = week[0].add((BigDecimal) r[1]);
                week[1] = week[1].add((BigDecimal) r[2]);
            }
            if (!date.isBefore(since30)) {
                sold30 = sold30.add((BigDecimal) r[1]);
                revenue30 = revenue30.add((BigDecimal) r[2]);
            }
        }
        List<Warehouse.WeekSales> weekRows = new ArrayList<>();
        weeks.forEach((week, v) -> weekRows.add(new Warehouse.WeekSales(week, v[0], money(v[1]))));
        BigDecimal perDay = sold30.divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        BigDecimal daysLeft = perDay.signum() == 0 ? null : product.getStock().max(BigDecimal.ZERO).divide(perDay, 0, RoundingMode.DOWN);
        return new Warehouse.SalesStats(sold30, money(revenue30), lastSaleByProduct().get(product.getId()), daysLeft, weekRows);
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private Map<Long, LocalDateTime> lastSaleByProduct() {
        Map<Long, LocalDateTime> last = new HashMap<>();
        for (Object[] r : entityManager.createQuery(
                "select si.product.id, max(s.date) from SaleItem si join si.sale s group by si.product.id", Object[].class).getResultList()) {
            last.put((Long) r[0], (LocalDateTime) r[1]);
        }
        return last;
    }

    /** Margin on the price without VAT, the same way as the reports (pos.reports.cost-includes-vat). */
    private BigDecimal margin(Product p) {
        BigDecimal divisor = BigDecimal.ONE.add(p.getTaxRate().movePointLeft(2));
        BigDecimal priceExVat = p.getPrice().divide(divisor, 4, RoundingMode.HALF_UP);
        BigDecimal costExVat = costIncludesVat ? p.getPurchasePrice().divide(divisor, 4, RoundingMode.HALF_UP) : p.getPurchasePrice();
        if (priceExVat.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return priceExVat.subtract(costExVat).multiply(BigDecimal.valueOf(100)).divide(priceExVat, 1, RoundingMode.HALF_UP);
    }

    /** A price per kilogram or litre, e.g. "për litër 240". */
    private record UnitPrice(BigDecimal amount, String label) {
        String text() {
            return label + " " + labelMoney(amount);
        }
    }

    /**
     * Price per kilogram or litre of a price for "pieces" pieces, from what one piece contains. Null for products
     * sold by weight (their price is already per kg) and for products without their content set.
     */
    private UnitPrice unitPrice(Product p, BigDecimal price, int pieces) {
        if (Quantities.KILOGRAMS.equals(p.getUnit())) {
            return null;
        }
        if (p.getContentAmount() == null || p.getContentUnit() == null) {
            return null;
        }
        BigDecimal content = p.getContentAmount().multiply(BigDecimal.valueOf(pieces));
        String unit = p.getContentUnit();
        if ("g".equals(unit) || "ml".equals(unit)) {
            content = content.movePointLeft(3);
        }
        if (content.signum() <= 0) {
            return null;
        }
        String label = text("g".equals(unit) || "kg".equals(unit) ? "label.perKg" : "label.perLitre");
        return new UnitPrice(price.divide(content, 2, RoundingMode.HALF_UP), label);
    }

    private List<Object[]> list(String jpql, Long id) {
        return entityManager.createQuery(jpql, Object[].class).setParameter("id", id).getResultList();
    }

    private static BigDecimal round(BigDecimal price, BigDecimal roundTo) {
        if (roundTo.signum() <= 0) {
            return price.setScale(2, RoundingMode.HALF_UP);
        }
        return price.divide(roundTo, 0, RoundingMode.HALF_UP).multiply(roundTo).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String text(String code, Object... args) {
        return messageSource.getMessage(code, args.length == 0 ? null : args, code, LocaleContextHolder.getLocale());
    }

    /** Prices on labels: 1250 or 99.50 (no ".00" on whole amounts). */
    static String labelMoney(BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        return value.stripTrailingZeros().scale() <= 0 ? value.setScale(0, RoundingMode.HALF_UP).toPlainString() : value.toPlainString();
    }
}

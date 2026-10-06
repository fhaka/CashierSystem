package com.supermarket.service;

import com.supermarket.dto.Analytics;
import com.supermarket.dto.Analytics.DayRow;
import com.supermarket.dto.Analytics.DeadStockRow;
import com.supermarket.dto.Analytics.GroupRow;
import com.supermarket.dto.Analytics.HourRow;
import com.supermarket.dto.Analytics.Kpis;
import com.supermarket.dto.Analytics.ProductRow;
import com.supermarket.model.PaymentMethod;
import com.supermarket.model.Product;
import com.supermarket.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sales analysis calculated in the database (grouped queries), so the screen never downloads every sale.
 * Refunds made in the period are subtracted from the products they returned.
 */
@Service
public class AnalyticsService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager entityManager;
    private final ProductRepository productRepository;
    private final boolean costIncludesVat;

    public AnalyticsService(EntityManager entityManager, ProductRepository productRepository,
                            @Value("${pos.reports.cost-includes-vat:true}") boolean costIncludesVat) {
        this.entityManager = entityManager;
        this.productRepository = productRepository;
        this.costIncludesVat = costIncludesVat;
    }

    @Transactional(readOnly = true)
    public Analytics analyze(LocalDate fromDay, LocalDate toDay) {
        LocalDateTime from = fromDay.atStartOfDay();
        LocalDateTime to = toDay.atTime(LocalTime.MAX);

        // Per product: quantity, amount paid, VAT and cost of what was sold, minus what was refunded.
        Map<Long, ProductAcc> products = new LinkedHashMap<>();
        for (Object[] r : list("""
                select p.id, p.name, p.barcode, p.unit, c.name, sum(si.quantity), sum(si.lineTotal), sum(si.taxAmount),
                       sum(si.quantity * coalesce(si.purchasePrice, 0)),
                       sum(si.quantity * coalesce(si.purchasePrice, 0) * 100 / (100 + si.taxRate))
                from SaleItem si join si.sale s join si.product p left join p.category c
                where s.date >= :from and s.date <= :to
                group by p.id, p.name, p.barcode, p.unit, c.name
                """, from, to)) {
            ProductAcc acc = products.computeIfAbsent((Long) r[0], id -> new ProductAcc((String) r[1], (String) r[2], (String) r[3], (String) r[4]));
            acc.add(dec(r[5]), dec(r[6]), dec(r[7]), dec(r[8]), dec(r[9]));
        }
        for (Object[] r : list("""
                select p.id, p.name, p.barcode, p.unit, c.name, sum(ri.quantity), sum(ri.amount), sum(ri.taxAmount),
                       sum(ri.quantity * coalesce(si.purchasePrice, 0)),
                       sum(ri.quantity * coalesce(si.purchasePrice, 0) * 100 / (100 + si.taxRate))
                from RefundItem ri join ri.refund rf join ri.saleItem si join si.product p left join p.category c
                where rf.createdAt >= :from and rf.createdAt <= :to
                group by p.id, p.name, p.barcode, p.unit, c.name
                """, from, to)) {
            ProductAcc acc = products.computeIfAbsent((Long) r[0], id -> new ProductAcc((String) r[1], (String) r[2], (String) r[3], (String) r[4]));
            acc.add(dec(r[5]).negate(), dec(r[6]).negate(), dec(r[7]).negate(), dec(r[8]).negate(), dec(r[9]).negate());
        }

        List<ProductRow> productRows = new ArrayList<>();
        Map<String, BigDecimal[]> categories = new TreeMap<>();
        BigDecimal vat = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal pieces = BigDecimal.ZERO;
        for (Map.Entry<Long, ProductAcc> entry : products.entrySet()) {
            ProductAcc a = entry.getValue();
            BigDecimal productCost = money(costIncludesVat ? a.costExVat : a.cost);
            BigDecimal productProfit = a.revenue.subtract(a.vat).subtract(productCost);
            productRows.add(new ProductRow(entry.getKey(), a.name, a.barcode, a.unit, a.category, a.quantity, money(a.revenue),
                    money(a.vat), productCost, money(productProfit), percent(productProfit, a.revenue.subtract(a.vat))));
            BigDecimal[] cat = categories.computeIfAbsent(a.category == null ? "-" : a.category,
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            cat[0] = cat[0].add(a.quantity);
            cat[1] = cat[1].add(a.revenue);
            cat[2] = cat[2].add(productProfit);
            vat = vat.add(a.vat);
            cost = cost.add(productCost);
            profit = profit.add(productProfit);
            if (!"kg".equals(a.unit)) {
                pieces = pieces.add(a.quantity);
            }
        }
        productRows.sort(Comparator.comparing(ProductRow::revenue).reversed());

        // Per sale: day, hour and cashier.
        Map<LocalDate, BigDecimal[]> days = new TreeMap<>();
        BigDecimal[][] hours = new BigDecimal[24][];
        Map<String, BigDecimal[]> cashiers = new TreeMap<>();
        long salesCount = 0;
        BigDecimal grossSales = BigDecimal.ZERO;
        for (Object[] r : list("""
                select s.date, s.totalAmount, c.fullName from Sale s left join s.cashier c
                where s.date >= :from and s.date <= :to
                """, from, to)) {
            LocalDateTime date = (LocalDateTime) r[0];
            BigDecimal total = dec(r[1]);
            salesCount++;
            grossSales = grossSales.add(total);
            addTo(days.computeIfAbsent(date.toLocalDate(), d -> zeros()), total);
            if (hours[date.getHour()] == null) {
                hours[date.getHour()] = zeros();
            }
            addTo(hours[date.getHour()], total);
            addTo(cashiers.computeIfAbsent(r[2] == null ? "-" : (String) r[2], k -> zeros()), total);
        }

        // Payments: what came in by each method (cash net of change).
        Map<String, BigDecimal[]> payments = new LinkedHashMap<>();
        for (Object[] r : list("""
                select pm.method, count(pm), sum(pm.amountLek) from SalePayment pm join pm.sale s
                where s.date >= :from and s.date <= :to group by pm.method
                """, from, to)) {
            payments.put(((PaymentMethod) r[0]).name(), new BigDecimal[] {BigDecimal.valueOf((Long) r[1]), dec(r[2])});
        }
        BigDecimal change = dec(single("select sum(s.changeAmount) from Sale s where s.date >= :from and s.date <= :to", from, to));
        if (payments.containsKey("CASH")) {
            payments.get("CASH")[1] = payments.get("CASH")[1].subtract(change);
        }
        BigDecimal discounts = dec(single("select sum(s.discountAmount) from Sale s where s.date >= :from and s.date <= :to", from, to));
        Object[] refundTotals = (Object[]) entityManager.createQuery(
                        "select count(rf), coalesce(sum(rf.totalAmount), 0) from Refund rf where rf.createdAt >= :from and rf.createdAt <= :to")
                .setParameter("from", from).setParameter("to", to).getSingleResult();
        int refundsCount = ((Long) refundTotals[0]).intValue();
        BigDecimal refunds = dec(refundTotals[1]);
        BigDecimal revenue = grossSales.subtract(refunds);

        Kpis kpis = new Kpis(salesCount, money(grossSales), money(discounts), money(refunds), refundsCount, money(revenue),
                money(vat), money(revenue.subtract(vat)), money(cost), money(profit), percent(profit, revenue.subtract(vat)),
                salesCount == 0 ? BigDecimal.ZERO.setScale(2) : grossSales.divide(BigDecimal.valueOf(salesCount), 2, RoundingMode.HALF_UP),
                pieces.stripTrailingZeros());

        List<DayRow> dayRows = new ArrayList<>();
        days.forEach((day, v) -> dayRows.add(new DayRow(day, v[0].longValue(), money(v[1]))));
        List<HourRow> hourRows = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hourRows.add(new HourRow(h, hours[h] == null ? 0 : hours[h][0].longValue(), hours[h] == null ? BigDecimal.ZERO : money(hours[h][1])));
        }
        List<GroupRow> cashierRows = new ArrayList<>();
        cashiers.forEach((name, v) -> cashierRows.add(new GroupRow(name, v[0].longValue(), money(v[1]), null)));
        cashierRows.sort(Comparator.comparing(GroupRow::revenue).reversed());
        List<GroupRow> categoryRows = new ArrayList<>();
        categories.forEach((name, v) -> categoryRows.add(new GroupRow(name, v[0].longValue(), money(v[1]), money(v[2]))));
        categoryRows.sort(Comparator.comparing(GroupRow::revenue).reversed());
        List<GroupRow> paymentRows = new ArrayList<>();
        payments.forEach((method, v) -> paymentRows.add(new GroupRow(method, v[0].longValue(), money(v[1]), null)));

        return new Analytics(fromDay, toDay, kpis, dayRows, hourRows, cashierRows, categoryRows, paymentRows, productRows,
                deadStock(products.keySet()));
    }

    /** Active products in stock that were not sold in the period, most money tied up first. */
    private List<DeadStockRow> deadStock(Set<Long> soldIds) {
        Set<Long> sold = new HashSet<>(soldIds);
        return productRepository.findByActiveTrue().stream()
                .filter(p -> p.getStock().signum() > 0 && !sold.contains(p.getId()))
                .map(p -> new DeadStockRow(p.getId(), p.getName(), p.getBarcode(), p.getUnit(),
                        p.getCategory() == null ? null : p.getCategory().getName(), p.getStock(),
                        money(p.getStock().multiply(p.getPurchasePrice()))))
                .sorted(Comparator.comparing(DeadStockRow::value).reversed())
                .limit(100)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> list(String query, LocalDateTime from, LocalDateTime to) {
        return entityManager.createQuery(query).setParameter("from", from).setParameter("to", to).getResultList();
    }

    private Object single(String query, LocalDateTime from, LocalDateTime to) {
        return entityManager.createQuery(query).setParameter("from", from).setParameter("to", to).getSingleResult();
    }

    private static BigDecimal dec(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO.setScale(1) : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static void addTo(BigDecimal[] acc, BigDecimal amount) {
        acc[0] = acc[0].add(BigDecimal.ONE);
        acc[1] = acc[1].add(amount);
    }

    private static final class ProductAcc {
        private final String name;
        private final String barcode;
        private final String unit;
        private final String category;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal vat = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private BigDecimal costExVat = BigDecimal.ZERO;

        private ProductAcc(String name, String barcode, String unit, String category) {
            this.name = name;
            this.barcode = barcode;
            this.unit = unit;
            this.category = category;
        }

        private void add(BigDecimal quantity, BigDecimal revenue, BigDecimal vat, BigDecimal cost, BigDecimal costExVat) {
            this.quantity = this.quantity.add(quantity);
            this.revenue = this.revenue.add(revenue);
            this.vat = this.vat.add(vat);
            this.cost = this.cost.add(cost);
            this.costExVat = this.costExVat.add(costExVat);
        }
    }
}

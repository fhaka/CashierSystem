package com.supermarket.service;

import com.supermarket.dto.Accounting.Line;
import com.supermarket.dto.Accounting.Month;
import com.supermarket.dto.Accounting.ProfitAndLoss;
import com.supermarket.dto.Accounting.ProfitAndLossCompare;
import com.supermarket.dto.Accounting.PurchaseBook;
import com.supermarket.dto.Accounting.PurchaseRow;
import com.supermarket.dto.Accounting.RateAmount;
import com.supermarket.dto.Accounting.SalesBook;
import com.supermarket.dto.Accounting.SalesDay;
import com.supermarket.dto.Accounting.SalesDocument;
import com.supermarket.dto.Accounting.StockCategory;
import com.supermarket.dto.Accounting.StockProduct;
import com.supermarket.dto.Accounting.StockValue;
import com.supermarket.dto.Analytics;
import com.supermarket.dto.ExpenseDtos.CategoryTotal;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Product;
import com.supermarket.model.StockAdjustment;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Kontabiliteti: one month for the accountant. Profit and loss (from the sales analysis, the stock losses and the
 * expenses), the sales and purchase books by VAT rate, and the stock value at the start and end of the month.
 */
@Service
public class AccountingService {

    /** The VAT rates products can have, always shown as columns of the books (any other rate in old data appears too). */
    private static final List<BigDecimal> STANDARD_RATES = List.of(new BigDecimal("0.00"), new BigDecimal("20.00"));
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager entityManager;
    private final AnalyticsService analyticsService;
    private final ExpenseService expenseService;
    private final boolean costIncludesVat;

    public AccountingService(EntityManager entityManager, AnalyticsService analyticsService, ExpenseService expenseService,
                             @Value("${pos.reports.cost-includes-vat:true}") boolean costIncludesVat) {
        this.entityManager = entityManager;
        this.analyticsService = analyticsService;
        this.expenseService = expenseService;
        this.costIncludesVat = costIncludesVat;
    }

    /** month: yyyy-MM; by default this month. */
    public static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            YearMonth parsed = YearMonth.parse(month.trim());
            if (parsed.isAfter(YearMonth.now())) {
                throw new ValidationException("accounting.futureMonth");
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw new ValidationException("accounting.invalidMonth", month);
        }
    }

    @Transactional(readOnly = true)
    public Month month(YearMonth month) {
        return new Month(month.toString(), new ProfitAndLossCompare(profitAndLoss(month), profitAndLoss(month.minusMonths(1))),
                salesBook(month), purchaseBook(month), stockValue(month.atEndOfMonth()), stockValue(month.atDay(1).minusDays(1)),
                openingStockEntered(month));
    }

    /** Opening stock of the products created in the month, at cost without VAT: goods that came in without a purchase invoice. */
    @Transactional(readOnly = true)
    public BigDecimal openingStockEntered(YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] r : entityManager.createQuery("""
                        select a.quantityChange, coalesce(a.unitCost, p.purchasePrice), p.taxRate
                        from StockAdjustment a join a.product p where a.createdAt >= :from and a.createdAt <= :to
                          and a.reason = com.supermarket.model.StockAdjustment.Reason.OPENING_STOCK""", Object[].class)
                .setParameter("from", month.atDay(1).atStartOfDay()).setParameter("to", month.atEndOfMonth().atTime(LocalTime.MAX))
                .getResultList()) {
            total = total.add(((BigDecimal) r[0]).multiply(costWithoutVat((BigDecimal) r[1], (BigDecimal) r[2])));
        }
        return money(total);
    }

    // --- Profit and loss -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ProfitAndLoss profitAndLoss(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        Analytics.Kpis k = analyticsService.analyze(from, to).kpis();

        // Stock that left the shelf without being sold, and count differences, at the cost of that day (today's cost for
        // adjustments made before 1.4). The opening stock of new products is not a loss.
        Map<String, BigDecimal[]> losses = new LinkedHashMap<>();
        for (Object[] r : entityManager.createQuery("""
                        select a.reason, a.inventoryCountId, a.quantityChange, coalesce(a.unitCost, p.purchasePrice), p.taxRate
                        from StockAdjustment a join a.product p where a.createdAt >= :from and a.createdAt <= :to
                          and a.reason <> com.supermarket.model.StockAdjustment.Reason.OPENING_STOCK""", Object[].class)
                .setParameter("from", from.atStartOfDay()).setParameter("to", to.atTime(LocalTime.MAX)).getResultList()) {
            StockAdjustment.Reason reason = (StockAdjustment.Reason) r[0];
            String key = r[1] != null || reason == StockAdjustment.Reason.COUNT_CORRECTION ? "COUNT" : reason.name();
            BigDecimal value = ((BigDecimal) r[2]).negate().multiply(costWithoutVat((BigDecimal) r[3], (BigDecimal) r[4]));
            BigDecimal[] acc = losses.computeIfAbsent(key, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            acc[0] = acc[0].add(value);
            acc[1] = acc[1].add(BigDecimal.ONE);
        }
        List<Line> lossLines = new ArrayList<>();
        BigDecimal lossTotal = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : losses.entrySet()) {
            BigDecimal amount = money(e.getValue()[0]);
            lossLines.add(new Line(e.getKey(), null, amount, e.getValue()[1].intValue()));
            lossTotal = lossTotal.add(amount);
        }
        lossLines.sort(Comparator.comparing(Line::amount).reversed());

        List<Line> expenseLines = new ArrayList<>();
        BigDecimal expenseTotal = BigDecimal.ZERO;
        for (CategoryTotal c : expenseService.list(from, to, null, false).byCategory()) {
            expenseLines.add(new Line(String.valueOf(c.categoryId()), c.category(), c.amount(), c.count()));
            expenseTotal = expenseTotal.add(c.amount());
        }

        BigDecimal grossProfit = k.profit();
        BigDecimal netProfit = grossProfit.subtract(lossTotal).subtract(expenseTotal);
        return new ProfitAndLoss(from, to, k.salesCount(), k.grossSales(), k.discounts(), k.refunds(), k.revenue(), k.vat(),
                k.revenueWithoutVat(), k.cost(), grossProfit, k.marginPercent(), lossLines, money(lossTotal), expenseLines,
                money(expenseTotal), money(netProfit), percent(netProfit, k.revenueWithoutVat()));
    }

    // --- Sales book ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SalesBook salesBook(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.atEndOfMonth().atTime(LocalTime.MAX);
        TreeSet<BigDecimal> rates = new TreeSet<>(STANDARD_RATES);

        // One document per receipt and per refund, with its amounts by rate.
        Map<String, DocAcc> docs = new LinkedHashMap<>();
        for (Object[] r : entityManager.createQuery("""
                        select s.id, s.date, s.invoiceNumber, cu.fullName, si.taxRate, sum(si.lineTotal), sum(si.taxAmount)
                        from SaleItem si join si.sale s left join s.customer cu where s.date >= :from and s.date <= :to
                        group by s.id, s.date, s.invoiceNumber, cu.fullName, si.taxRate""", Object[].class)
                .setParameter("from", from).setParameter("to", to).getResultList()) {
            DocAcc doc = docs.computeIfAbsent("S" + r[0], x -> new DocAcc((LocalDateTime) r[1], (String) r[2], "SALE", (String) r[3], null));
            rates.add(rate((BigDecimal) r[4]));
            doc.add(rate((BigDecimal) r[4]), (BigDecimal) r[5], (BigDecimal) r[6]);
        }
        for (Object[] r : entityManager.createQuery("""
                        select rf.id, rf.createdAt, rf.refundNumber, s.invoiceNumber, cu.fullName, ri.taxRate, sum(ri.amount), sum(ri.taxAmount)
                        from RefundItem ri join ri.refund rf left join rf.sale s left join s.customer cu
                        where rf.createdAt >= :from and rf.createdAt <= :to
                        group by rf.id, rf.createdAt, rf.refundNumber, s.invoiceNumber, cu.fullName, ri.taxRate""", Object[].class)
                .setParameter("from", from).setParameter("to", to).getResultList()) {
            DocAcc doc = docs.computeIfAbsent("R" + r[0], x -> new DocAcc((LocalDateTime) r[1], (String) r[2], "REFUND", (String) r[4], (String) r[3]));
            rates.add(rate((BigDecimal) r[5]));
            doc.add(rate((BigDecimal) r[5]), ((BigDecimal) r[6]).negate(), ((BigDecimal) r[7]).negate());
        }
        List<DocAcc> ordered = new ArrayList<>(docs.values());
        ordered.sort(Comparator.comparing((DocAcc d) -> d.at).thenComparing(d -> d.number == null ? "" : d.number));

        Map<LocalDate, DayAcc> days = new TreeMap<>();
        RateAcc totals = new RateAcc();
        List<SalesDocument> documents = new ArrayList<>();
        for (DocAcc d : ordered) {
            DayAcc day = days.computeIfAbsent(d.at.toLocalDate(), x -> new DayAcc());
            if ("SALE".equals(d.kind)) {
                day.receipts++;
                if (day.first == null) {
                    day.first = d.number;
                }
                day.last = d.number;
            } else {
                day.refunds++;
            }
            day.amounts.addAll(d.amounts);
            totals.addAll(d.amounts);
            documents.add(new SalesDocument(d.at, d.number, d.kind, d.customer, d.forInvoice, d.amounts.list(rates),
                    d.amounts.base(), d.amounts.vat(), d.amounts.total()));
        }
        List<SalesDay> dayRows = new ArrayList<>();
        days.forEach((day, a) -> dayRows.add(new SalesDay(day, a.receipts, a.first, a.last, a.refunds, a.amounts.list(rates),
                a.amounts.base(), a.amounts.vat(), a.amounts.total())));
        return new SalesBook(month.atDay(1), month.atEndOfMonth(), new ArrayList<>(rates), dayRows, documents, totals.list(rates),
                totals.base(), totals.vat(), totals.total());
    }

    // --- Purchase book -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PurchaseBook purchaseBook(YearMonth month) {
        TreeSet<BigDecimal> rates = new TreeSet<>(STANDARD_RATES);
        Map<Long, Object[]> heads = new LinkedHashMap<>();
        Map<Long, RateAcc> amounts = new HashMap<>();
        for (Object[] r : entityManager.createQuery("""
                        select inv.id, inv.invoiceDate, inv.invoiceNumber, coalesce(sup.name, inv.company), sup.taxNumber,
                               pi.taxRate, sum(pi.lineTotal)
                        from PurchaseItem pi join pi.invoice inv left join inv.supplier sup
                        where inv.invoiceDate >= :from and inv.invoiceDate <= :to
                        group by inv.id, inv.invoiceDate, inv.invoiceNumber, coalesce(sup.name, inv.company), sup.taxNumber, pi.taxRate
                        order by inv.invoiceDate, inv.id""", Object[].class)
                .setParameter("from", month.atDay(1)).setParameter("to", month.atEndOfMonth()).getResultList()) {
            Long id = (Long) r[0];
            heads.putIfAbsent(id, r);
            BigDecimal rate = rate((BigDecimal) r[5]);
            rates.add(rate);
            BigDecimal amount = (BigDecimal) r[6];
            // The price on a purchase line includes VAT unless pos.reports.cost-includes-vat=false.
            BigDecimal base = costIncludesVat ? withoutVat(amount, rate) : amount;
            BigDecimal total = costIncludesVat ? amount : money(amount.multiply(HUNDRED.add(rate)).divide(HUNDRED, 4, RoundingMode.HALF_UP));
            amounts.computeIfAbsent(id, x -> new RateAcc()).add(rate, total, total.subtract(base));
        }
        RateAcc totals = new RateAcc();
        List<PurchaseRow> rows = new ArrayList<>();
        heads.forEach((id, r) -> {
            RateAcc a = amounts.get(id);
            totals.addAll(a);
            rows.add(new PurchaseRow(id, (LocalDate) r[1], (String) r[2], (String) r[3], (String) r[4], a.list(rates), a.base(), a.vat(), a.total()));
        });
        return new PurchaseBook(month.atDay(1), month.atEndOfMonth(), new ArrayList<>(rates), rows, totals.list(rates),
                totals.base(), totals.vat(), totals.total());
    }

    // --- Stock value ---------------------------------------------------------------------------------------------

    /**
     * Stock at the end of a day: today's stock minus everything that happened after it (sales put back, refunds,
     * purchases dated later and adjustments taken out). Valued at the cost of that moment from the price history.
     */
    @Transactional(readOnly = true)
    public StockValue stockValue(LocalDate day) {
        LocalDate end = day.isAfter(LocalDate.now()) ? LocalDate.now() : day;
        LocalDateTime after = end.atTime(LocalTime.MAX);
        Map<Long, BigDecimal> change = new HashMap<>();
        sumInto(change, "select si.product.id, sum(si.quantity) from SaleItem si join si.sale s where s.date > :t group by si.product.id",
                after, BigDecimal.ONE);
        sumInto(change, """
                select si.product.id, sum(ri.quantity) from RefundItem ri join ri.refund rf join ri.saleItem si
                where rf.createdAt > :t group by si.product.id""", after, BigDecimal.ONE.negate());
        sumInto(change, "select a.product.id, sum(a.quantityChange) from StockAdjustment a where a.createdAt > :t group by a.product.id",
                after, BigDecimal.ONE.negate());
        for (Object[] r : entityManager.createQuery("""
                        select pi.product.id, sum(pi.quantity) from PurchaseItem pi join pi.invoice inv
                        where inv.invoiceDate > :d group by pi.product.id""", Object[].class)
                .setParameter("d", end).getResultList()) {
            change.merge((Long) r[0], ((BigDecimal) r[1]).negate(), BigDecimal::add);
        }

        // Cost and existence at that moment, from the price history of the piece (not of boxes).
        Map<Long, BigDecimal> costAt = new HashMap<>();
        Map<Long, BigDecimal> laterOldCost = new HashMap<>();
        Map<Long, LocalDateTime> createdAt = new HashMap<>();
        for (Object[] r : entityManager.createQuery("""
                        select pc.productId, pc.changedAt, pc.oldPurchasePrice, pc.newPurchasePrice, pc.oldPrice
                        from PriceChange pc where pc.packageId is null order by pc.changedAt, pc.id""", Object[].class).getResultList()) {
            Long id = (Long) r[0];
            LocalDateTime at = (LocalDateTime) r[1];
            if (r[2] == null && r[4] == null) {
                createdAt.putIfAbsent(id, at);
            }
            if (r[3] == null) {
                continue;
            }
            if (!at.isAfter(after)) {
                costAt.put(id, (BigDecimal) r[3]);
            } else if (r[2] != null) {
                laterOldCost.putIfAbsent(id, (BigDecimal) r[2]);
            }
        }

        Map<String, BigDecimal[]> categories = new TreeMap<>();
        List<StockProduct> products = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal valueWithoutVat = BigDecimal.ZERO;
        List<Product> all = entityManager.createQuery("select p from Product p left join fetch p.category order by p.name", Product.class)
                .getResultList();
        for (Product p : all) {
            LocalDateTime created = createdAt.get(p.getId());
            if (created != null && created.isAfter(after)) {
                continue;
            }
            BigDecimal stock = p.getStock().add(change.getOrDefault(p.getId(), BigDecimal.ZERO));
            if (stock.signum() <= 0) {
                continue;
            }
            BigDecimal cost = costAt.getOrDefault(p.getId(), laterOldCost.getOrDefault(p.getId(), p.getPurchasePrice()));
            BigDecimal productValue = money(stock.multiply(cost));
            BigDecimal productValueWithoutVat = costIncludesVat ? withoutVat(productValue, rate(p.getTaxRate())) : productValue;
            String category = p.getCategory() == null ? "-" : p.getCategory().getName();
            products.add(new StockProduct(p.getId(), p.getName(), p.getBarcode(), p.getUnit(), category, stock.stripTrailingZeros(),
                    cost, productValue, productValueWithoutVat));
            BigDecimal[] c = categories.computeIfAbsent(category, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            c[0] = c[0].add(BigDecimal.ONE);
            c[1] = c[1].add(productValue);
            c[2] = c[2].add(productValueWithoutVat);
            value = value.add(productValue);
            valueWithoutVat = valueWithoutVat.add(productValueWithoutVat);
        }
        List<StockCategory> categoryRows = new ArrayList<>();
        categories.forEach((name, c) -> categoryRows.add(new StockCategory(name, c[0].intValue(), c[1], c[2])));
        categoryRows.sort(Comparator.comparing(StockCategory::value).reversed());
        return new StockValue(end, categoryRows, products, value, valueWithoutVat);
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private void sumInto(Map<Long, BigDecimal> into, String jpql, LocalDateTime after, BigDecimal sign) {
        for (Object[] r : entityManager.createQuery(jpql, Object[].class).setParameter("t", after).getResultList()) {
            into.merge((Long) r[0], ((BigDecimal) r[1]).multiply(sign), BigDecimal::add);
        }
    }

    private BigDecimal costWithoutVat(BigDecimal cost, BigDecimal rate) {
        return costIncludesVat ? cost.multiply(HUNDRED).divide(HUNDRED.add(rate), 4, RoundingMode.HALF_UP) : cost;
    }

    private static BigDecimal withoutVat(BigDecimal amount, BigDecimal rate) {
        return money(amount.multiply(HUNDRED).divide(HUNDRED.add(rate), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal rate(BigDecimal rate) {
        return rate.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO.setScale(1) : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Amounts with VAT and VAT, by rate. */
    private static final class RateAcc {
        private final Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();

        void add(BigDecimal rate, BigDecimal total, BigDecimal vat) {
            BigDecimal[] a = byRate.computeIfAbsent(rate, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(total);
            a[1] = a[1].add(vat);
        }

        void addAll(RateAcc other) {
            other.byRate.forEach((rate, a) -> add(rate, a[0], a[1]));
        }

        List<RateAmount> list(Iterable<BigDecimal> rates) {
            List<RateAmount> out = new ArrayList<>();
            for (BigDecimal rate : rates) {
                BigDecimal[] a = byRate.getOrDefault(rate, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                out.add(new RateAmount(rate, money(a[0].subtract(a[1])), money(a[1]), money(a[0])));
            }
            return out;
        }

        BigDecimal total() {
            return money(byRate.values().stream().map(a -> a[0]).reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        BigDecimal vat() {
            return money(byRate.values().stream().map(a -> a[1]).reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        BigDecimal base() {
            return total().subtract(vat());
        }
    }

    private static final class DocAcc {
        final LocalDateTime at;
        final String number;
        final String kind;
        final String customer;
        final String forInvoice;
        final RateAcc amounts = new RateAcc();

        DocAcc(LocalDateTime at, String number, String kind, String customer, String forInvoice) {
            this.at = at;
            this.number = number;
            this.kind = kind;
            this.customer = customer;
            this.forInvoice = forInvoice;
        }

        void add(BigDecimal rate, BigDecimal total, BigDecimal vat) {
            amounts.add(rate, total, vat);
        }
    }

    private static final class DayAcc {
        int receipts;
        int refunds;
        String first;
        String last;
        final RateAcc amounts = new RateAcc();
    }
}

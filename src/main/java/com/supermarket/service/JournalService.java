package com.supermarket.service;

import com.supermarket.dto.Journal;
import com.supermarket.dto.Journal.Entry;
import com.supermarket.dto.Journal.Type;
import com.supermarket.exception.ValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Regjistri i Veprimeve: sales, refunds, purchase invoices, stock adjustments and counts, cash in and out, supplier
 * and customer debt payments, shifts and price changes, newest first, in one list with totals. Each kind is read
 * from its own table for the chosen days; a filter by user or product leaves out the kinds that have none.
 */
@Service
public class JournalService {

    /** Longest period one request may cover. */
    public static final int MAX_DAYS = 366;

    private final EntityManager entityManager;

    public JournalService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Journal.Page page(Journal.Filter filter, int page, int size) {
        List<Entry> all = entries(filter);
        int pageSize = Math.max(1, Math.min(500, size));
        int from = Math.min(all.size(), Math.max(0, page) * pageSize);
        return new Journal.Page(all.subList(from, Math.min(all.size(), from + pageSize)), all.size(), Math.max(0, page), pageSize,
                totals(all), days(all));
    }

    /** All matching operations, newest first. */
    @Transactional(readOnly = true)
    public List<Entry> entries(Journal.Filter filter) {
        LocalDate to = filter.to() == null ? LocalDate.now() : filter.to();
        LocalDate from = filter.from() == null ? to.minusDays(6) : filter.from();
        if (from.isAfter(to)) {
            throw new ValidationException("journal.invalidPeriod");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ValidationException("journal.periodTooLong", MAX_DAYS);
        }
        Set<Type> types = filter.types() == null || filter.types().isEmpty() ? EnumSet.allOf(Type.class) : EnumSet.copyOf(filter.types());
        Range range = new Range(from.atStartOfDay(), to.atTime(LocalTime.MAX), from, to, filter.cashierId(), filter.productId());

        List<Entry> all = new ArrayList<>();
        if (types.contains(Type.SALE)) sales(range, all);
        if (types.contains(Type.REFUND)) refunds(range, all);
        if (types.contains(Type.PURCHASE) && range.cashierId == null) purchases(range, all);
        if (types.contains(Type.ADJUSTMENT)) adjustments(range, all);
        if (types.contains(Type.COUNT)) counts(range, all);
        if (range.productId == null) {
            if (types.contains(Type.CASH_IN) || types.contains(Type.CASH_OUT)) cash(range, types, all);
            if (types.contains(Type.SUPPLIER_PAYMENT)) supplierPayments(range, all);
            if (types.contains(Type.DEBT_PAYMENT)) debtPayments(range, all);
            if (types.contains(Type.SHIFT_OPEN) || types.contains(Type.SHIFT_CLOSE)) shifts(range, types, all);
        }
        if (types.contains(Type.PRICE_CHANGE)) priceChanges(range, all);

        String q = filter.query() == null ? "" : filter.query().trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(e -> q.isEmpty() || matches(e, q))
                .sorted(Comparator.comparing(Entry::at).reversed().thenComparing(e -> e.type().ordinal()))
                .toList();
    }

    // --- one method per kind -------------------------------------------------------------------------------------

    private void sales(Range r, List<Entry> out) {
        String jpql = """
                select s.id, s.date, s.invoiceNumber, s.totalAmount, c.fullName, cu.fullName, size(s.items)
                from Sale s left join s.cashier c left join s.customer cu
                where s.date >= :from and s.date <= :to""" + r.cashier("c") + r.product("exists (select 1 from SaleItem x where x.sale = s and x.product.id = :productId)");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            out.add(new Entry(Type.SALE, (LocalDateTime) row[1], (String) row[2], (String) row[5], null, null, null,
                    ((Number) row[6]).intValue(), null, (String) row[4], (BigDecimal) row[3], null, null, (Long) row[0]));
        }
    }

    private void refunds(Range r, List<Entry> out) {
        String jpql = """
                select rf.id, rf.createdAt, rf.refundNumber, rf.totalAmount, c.fullName, s.invoiceNumber, rf.reason, size(rf.items),
                       a.fullName
                from Refund rf left join rf.cashier c left join rf.sale s left join rf.approvedBy a
                where rf.createdAt >= :from and rf.createdAt <= :to""" + r.cashier("c")
                + r.product("exists (select 1 from RefundItem x join x.saleItem si where x.refund = rf and si.product.id = :productId)");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            String approved = row[8] != null && !row[8].equals(row[4]) ? " (" + row[8] + ")" : "";
            out.add(new Entry(Type.REFUND, (LocalDateTime) row[1], (String) row[2], (String) row[5], null, null, null,
                    ((Number) row[7]).intValue(), row[6] + approved, (String) row[4], negate((BigDecimal) row[3]), null, null, (Long) row[0]));
        }
    }

    private void purchases(Range r, List<Entry> out) {
        String jpql = """
                select inv.id, inv.invoiceDate, inv.invoiceNumber, inv.totalAmount, coalesce(sup.name, inv.company), size(inv.items)
                from PurchaseInvoice inv left join inv.supplier sup
                where inv.invoiceDate >= :fromDay and inv.invoiceDate <= :toDay"""
                + r.product("exists (select 1 from PurchaseItem x where x.invoice = inv and x.product.id = :productId)");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("fromDay", r.fromDay).setParameter("toDay", r.toDay);
        if (r.productId != null) {
            query.setParameter("productId", r.productId);
        }
        for (Object[] row : query.getResultList()) {
            out.add(new Entry(Type.PURCHASE, ((LocalDate) row[1]).atStartOfDay(), (String) row[2], (String) row[4], null, null, null,
                    ((Number) row[5]).intValue(), null, null, negate((BigDecimal) row[3]), null, null, (Long) row[0]));
        }
    }

    /** Adjustments by hand; those made by an inventory count are shown as one count. */
    private void adjustments(Range r, List<Entry> out) {
        String jpql = """
                select a.id, a.createdAt, a.reason, a.quantityChange, a.note, c.fullName, p.id, p.name, p.purchasePrice
                from StockAdjustment a join a.product p left join a.cashier c
                where a.inventoryCountId is null and a.createdAt >= :from and a.createdAt <= :to""" + r.cashier("c") + r.product("p.id = :productId");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            BigDecimal change = (BigDecimal) row[3];
            out.add(new Entry(Type.ADJUSTMENT, (LocalDateTime) row[1], String.valueOf(row[2]), null, (Long) row[6], (String) row[7],
                    change, null, (String) row[4], (String) row[5], money(change.multiply((BigDecimal) row[8])), null, null, (Long) row[0]));
        }
    }

    private void counts(Range r, List<Entry> out) {
        String jpql = """
                select ic.id, ic.finishedAt, ic.note, c.fullName,
                       (select count(a) from StockAdjustment a where a.inventoryCountId = ic.id),
                       (select coalesce(sum(a.quantityChange * a.product.purchasePrice), 0) from StockAdjustment a where a.inventoryCountId = ic.id)
                from InventoryCount ic left join ic.finishedBy c
                where ic.status = com.supermarket.model.InventoryCount.Status.APPLIED
                  and ic.finishedAt >= :from and ic.finishedAt <= :to""" + r.cashier("c")
                + r.product("exists (select 1 from InventoryCountLine l where l.count = ic and l.product.id = :productId)");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            out.add(new Entry(Type.COUNT, (LocalDateTime) row[1], "#" + row[0], null, null, null, null, ((Number) row[4]).intValue(),
                    (String) row[2], (String) row[3], money((BigDecimal) row[5]), null, null, (Long) row[0]));
        }
    }

    private void cash(Range r, Set<Type> types, List<Entry> out) {
        String jpql = """
                select m.shift.id, m.createdAt, m.type, m.amount, m.reason, c.fullName
                from CashMovement m left join m.cashier c
                where m.createdAt >= :from and m.createdAt <= :to""" + r.cashier("c");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            boolean in = "IN".equals(String.valueOf(row[2]));
            Type type = in ? Type.CASH_IN : Type.CASH_OUT;
            if (types.contains(type)) {
                BigDecimal amount = (BigDecimal) row[3];
                out.add(new Entry(type, (LocalDateTime) row[1], null, null, null, null, null, null, (String) row[4], (String) row[5],
                        in ? amount : negate(amount), null, null, (Long) row[0]));
            }
        }
    }

    private void supplierPayments(Range r, List<Entry> out) {
        String jpql = """
                select sp.id, sp.createdAt, s.name, sp.amount, sp.method, sp.note, c.fullName
                from SupplierPayment sp join sp.supplier s left join sp.cashier c
                where sp.createdAt >= :from and sp.createdAt <= :to""" + r.cashier("c");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            String note = row[4] + (row[5] == null || ((String) row[5]).isBlank() ? "" : " - " + row[5]);
            out.add(new Entry(Type.SUPPLIER_PAYMENT, (LocalDateTime) row[1], null, (String) row[2], null, null, null, null, note,
                    (String) row[6], negate((BigDecimal) row[3]), null, null, (Long) row[0]));
        }
    }

    private void debtPayments(Range r, List<Entry> out) {
        String jpql = """
                select t.id, t.createdAt, cu.fullName, t.balanceChange, t.note, c.fullName
                from CustomerTransaction t join t.customer cu left join t.cashier c
                where t.type = com.supermarket.model.CustomerTransaction.Type.PAYMENT
                  and t.createdAt >= :from and t.createdAt <= :to""" + r.cashier("c");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            out.add(new Entry(Type.DEBT_PAYMENT, (LocalDateTime) row[1], null, (String) row[2], null, null, null, null, (String) row[4],
                    (String) row[5], negate((BigDecimal) row[3]), null, null, (Long) row[0]));
        }
    }

    private void shifts(Range r, Set<Type> types, List<Entry> out) {
        String jpql = """
                select sh.id, sh.openedAt, sh.closedAt, sh.openingCash, sh.closingCash, sh.difference, c.fullName
                from Shift sh left join sh.cashier c
                where ((sh.openedAt >= :from and sh.openedAt <= :to) or (sh.closedAt >= :from and sh.closedAt <= :to))""" + r.cashier("c");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            LocalDateTime opened = (LocalDateTime) row[1];
            LocalDateTime closed = (LocalDateTime) row[2];
            if (types.contains(Type.SHIFT_OPEN) && r.covers(opened)) {
                out.add(new Entry(Type.SHIFT_OPEN, opened, "#" + row[0], null, null, null, null, null, null, (String) row[6], null,
                        (BigDecimal) row[3], null, (Long) row[0]));
            }
            if (types.contains(Type.SHIFT_CLOSE) && closed != null && r.covers(closed)) {
                out.add(new Entry(Type.SHIFT_CLOSE, closed, "#" + row[0], null, null, null, null, null, null, (String) row[6], null,
                        (BigDecimal) row[4], (BigDecimal) row[5], (Long) row[0]));
            }
        }
    }

    private void priceChanges(Range r, List<Entry> out) {
        String jpql = """
                select pc.id, pc.changedAt, pc.source, p.id, p.name, pc.oldPrice, pc.newPrice, pc.oldPurchasePrice, pc.newPurchasePrice,
                       c.fullName, b.name
                from PriceChange pc join Product p on p.id = pc.productId left join pc.cashier c
                     left join ProductPackage b on b.id = pc.packageId
                where pc.changedAt >= :from and pc.changedAt <= :to""" + r.cashier("c") + r.product("p.id = :productId");
        for (Object[] row : r.run(entityManager.createQuery(jpql, Object[].class))) {
            boolean selling = row[6] != null;
            String name = row[10] == null ? (String) row[4] : row[4] + " (" + row[10] + ")";
            out.add(new Entry(Type.PRICE_CHANGE, (LocalDateTime) row[1], String.valueOf(row[2]), null, (Long) row[3], name, null, null,
                    selling ? "PRICE" : "COST", (String) row[9], null,
                    (BigDecimal) (selling ? row[5] : row[7]), (BigDecimal) (selling ? row[6] : row[8]), (Long) row[0]));
        }
    }

    // --- totals ------------------------------------------------------------------------------------------------------

    private static List<Journal.TypeTotal> totals(List<Entry> all) {
        Map<Type, long[]> counts = new LinkedHashMap<>();
        Map<Type, BigDecimal> amounts = new LinkedHashMap<>();
        for (Type type : Type.values()) {
            counts.put(type, new long[1]);
            amounts.put(type, BigDecimal.ZERO);
        }
        for (Entry e : all) {
            counts.get(e.type())[0]++;
            if (e.amount() != null) {
                amounts.merge(e.type(), e.amount(), BigDecimal::add);
            }
        }
        List<Journal.TypeTotal> totals = new ArrayList<>();
        counts.forEach((type, count) -> {
            if (count[0] > 0) {
                totals.add(new Journal.TypeTotal(type, count[0], money(amounts.get(type))));
            }
        });
        return totals;
    }

    /** Money in and out per day. Stock adjustments and counts are valued, not paid, so they do not count here. */
    private static List<Journal.DayTotal> days(List<Entry> all) {
        Map<LocalDate, Object[]> days = new TreeMap<>(Comparator.reverseOrder());
        for (Entry e : all) {
            Object[] day = days.computeIfAbsent(e.at().toLocalDate(), d -> new Object[] {0L, BigDecimal.ZERO, BigDecimal.ZERO});
            day[0] = (Long) day[0] + 1;
            boolean money = e.amount() != null && e.type() != Type.ADJUSTMENT && e.type() != Type.COUNT;
            if (money && e.amount().signum() > 0) {
                day[1] = ((BigDecimal) day[1]).add(e.amount());
            } else if (money) {
                day[2] = ((BigDecimal) day[2]).add(e.amount().negate());
            }
        }
        List<Journal.DayTotal> rows = new ArrayList<>();
        days.forEach((day, v) -> rows.add(new Journal.DayTotal(day, (Long) v[0], money((BigDecimal) v[1]), money((BigDecimal) v[2]))));
        return rows;
    }

    private static boolean matches(Entry e, String q) {
        for (String field : new String[] {e.number(), e.counterparty(), e.productName(), e.note(), e.user()}) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal negate(BigDecimal value) {
        return value == null ? null : value.negate();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** The period and the optional filters; adds the matching where-clauses and parameters to a query. */
    private record Range(LocalDateTime from, LocalDateTime to, LocalDate fromDay, LocalDate toDay, Long cashierId, Long productId) {

        String cashier(String alias) {
            return cashierId == null ? "" : " and " + alias + ".id = :cashierId";
        }

        String product(String condition) {
            return productId == null ? "" : " and " + condition;
        }

        boolean covers(LocalDateTime at) {
            return !at.isBefore(from) && !at.isAfter(to);
        }

        List<Object[]> run(TypedQuery<Object[]> query) {
            query.setParameter("from", from).setParameter("to", to);
            if (cashierId != null) {
                query.setParameter("cashierId", cashierId);
            }
            if (productId != null && query.getParameters().stream().anyMatch(p -> "productId".equals(p.getName()))) {
                query.setParameter("productId", productId);
            }
            return query.getResultList();
        }
    }
}

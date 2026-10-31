package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 1. Every stock adjustment keeps the cost per unit of its moment (unit_cost); the ones made before get today's cost.
 *    Purchase invoices keep when they were entered (created_at), taken from the audit log for the ones made before.
 * 2. Products created with stock before this version get that starting stock recorded as an OPENING_STOCK adjustment,
 *    so their history starts at zero: opening = stock now - purchased + sold - refunded - other adjustments. It is
 *    dated when the product was created (its first price record), or just before its first movement. A product whose
 *    numbers do not add up to a positive opening stock is left as it is.
 * Written in Java because working this out per product in SQL differs between MySQL and H2.
 */
public class V13__adjustment_cost_and_opening_stock extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (Statement st = db.createStatement()) {
            st.execute("ALTER TABLE stock_adjustments ADD COLUMN unit_cost DECIMAL(10,2)");
            st.execute("UPDATE stock_adjustments SET unit_cost = (SELECT p.purchase_price FROM products p WHERE p.id = stock_adjustments.product_id)");
            st.execute("ALTER TABLE purchase_invoices ADD COLUMN created_at DATETIME(6)");
            st.execute("""
                    UPDATE purchase_invoices SET created_at = (SELECT MIN(a.created_at) FROM audit_events a
                    WHERE a.action = 'PURCHASE_SAVED' AND a.entity_id = purchase_invoices.invoice_number)""");
        }

        Long cashier = single(db, "SELECT MIN(id) FROM cashiers");
        if (cashier == null) {
            return;   // an empty database: nothing was ever sold or bought
        }
        Map<Long, BigDecimal> purchased = sums(db, "SELECT product_id, SUM(quantity) FROM purchase_items GROUP BY product_id");
        Map<Long, BigDecimal> sold = sums(db, "SELECT product_id, SUM(quantity) FROM sale_items GROUP BY product_id");
        Map<Long, BigDecimal> refunded = sums(db, """
                SELECT si.product_id, SUM(ri.quantity) FROM refund_items ri JOIN sale_items si ON si.id = ri.sale_item_id
                GROUP BY si.product_id""");
        Map<Long, BigDecimal> adjusted = sums(db, "SELECT product_id, SUM(quantity_change) FROM stock_adjustments GROUP BY product_id");

        Map<Long, LocalDateTime> created = times(db, """
                SELECT product_id, MIN(changed_at) FROM price_changes
                WHERE package_id IS NULL AND old_price IS NULL AND old_purchase_price IS NULL GROUP BY product_id""");
        Map<Long, LocalDateTime> firstMove = new HashMap<>();
        for (String sql : new String[] {
                "SELECT si.product_id, MIN(s.date) FROM sale_items si JOIN sales s ON s.id = si.sale_id GROUP BY si.product_id",
                "SELECT product_id, MIN(created_at) FROM stock_adjustments GROUP BY product_id"}) {
            times(db, sql).forEach((id, at) -> firstMove.merge(id, at, (a, b) -> a.isBefore(b) ? a : b));
        }
        // A purchase counts from when it was entered if that was on its date, else from the start of its date.
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT pi.product_id, inv.invoice_date, inv.created_at FROM purchase_items pi
                JOIN purchase_invoices inv ON inv.id = pi.invoice_id""")) {
            while (rs.next()) {
                java.time.LocalDate date = rs.getDate(2).toLocalDate();
                Timestamp entered = rs.getTimestamp(3);
                LocalDateTime at = entered != null && entered.toLocalDateTime().toLocalDate().equals(date)
                        ? entered.toLocalDateTime() : date.atStartOfDay();
                firstMove.merge(rs.getLong(1), at, (a, b) -> a.isBefore(b) ? a : b);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        try (Statement st = db.createStatement();
             ResultSet products = st.executeQuery("SELECT id, stock, purchase_price FROM products");
             PreparedStatement insert = db.prepareStatement("""
                     INSERT INTO stock_adjustments (product_id, cashier_id, created_at, quantity_change, stock_after, reason, note, unit_cost)
                     VALUES (?, ?, ?, ?, ?, 'OPENING_STOCK', ?, ?)""")) {
            while (products.next()) {
                long id = products.getLong(1);
                BigDecimal opening = products.getBigDecimal(2)
                        .subtract(purchased.getOrDefault(id, BigDecimal.ZERO))
                        .add(sold.getOrDefault(id, BigDecimal.ZERO))
                        .subtract(refunded.getOrDefault(id, BigDecimal.ZERO))
                        .subtract(adjusted.getOrDefault(id, BigDecimal.ZERO));
                if (opening.compareTo(new BigDecimal("0.0005")) < 0) {
                    continue;
                }
                LocalDateTime at = created.containsKey(id) ? created.get(id)
                        : firstMove.containsKey(id) ? firstMove.get(id).minusSeconds(1) : now;
                insert.setLong(1, id);
                insert.setLong(2, cashier);
                insert.setTimestamp(3, Timestamp.valueOf(at));
                insert.setBigDecimal(4, opening);
                insert.setBigDecimal(5, opening);
                insert.setString(6, "Gjendja fillestare e llogaritur / worked out when updating to 1.4");
                insert.setBigDecimal(7, products.getBigDecimal(3));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Long single(Connection db, String sql) throws Exception {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }
    }

    private static Map<Long, BigDecimal> sums(Connection db, String sql) throws Exception {
        Map<Long, BigDecimal> out = new HashMap<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                BigDecimal value = rs.getBigDecimal(2);
                out.put(rs.getLong(1), value == null ? BigDecimal.ZERO : value);
            }
        }
        return out;
    }

    private static Map<Long, LocalDateTime> times(Connection db, String sql) throws Exception {
        Map<Long, LocalDateTime> out = new HashMap<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Timestamp at = rs.getTimestamp(2);
                if (at != null) {
                    out.put(rs.getLong(1), at.toLocalDateTime());
                }
            }
        }
        return out;
    }
}

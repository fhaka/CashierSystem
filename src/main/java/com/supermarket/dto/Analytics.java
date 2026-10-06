package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales analysis of a period, calculated on the server. Amounts in LEK; revenue figures are net of refunds.
 * Profit = revenue without VAT - cost without VAT (see pos.reports.cost-includes-vat).
 */
public record Analytics(
        LocalDate from,
        LocalDate to,
        Kpis kpis,
        List<DayRow> byDay,
        List<HourRow> byHour,
        List<GroupRow> byCashier,
        List<GroupRow> byCategory,
        List<GroupRow> byPayment,
        List<ProductRow> products,
        List<DeadStockRow> deadStock
) {

    public record Kpis(long salesCount, BigDecimal grossSales, BigDecimal discounts, BigDecimal refunds, int refundsCount,
                       BigDecimal revenue, BigDecimal vat, BigDecimal revenueWithoutVat, BigDecimal cost, BigDecimal profit,
                       BigDecimal marginPercent, BigDecimal averageSale, BigDecimal piecesSold) {
    }

    public record DayRow(LocalDate day, long salesCount, BigDecimal revenue) {
    }

    public record HourRow(int hour, long salesCount, BigDecimal revenue) {
    }

    /** One cashier, category or payment method. profit is null where it does not apply. */
    public record GroupRow(String name, long count, BigDecimal revenue, BigDecimal profit) {
    }

    public record ProductRow(Long productId, String name, String barcode, String unit, String category,
                             BigDecimal quantity, BigDecimal revenue, BigDecimal vat, BigDecimal cost, BigDecimal profit,
                             BigDecimal marginPercent) {
    }

    /** In stock but not sold in the period; value is stock x cost price. */
    public record DeadStockRow(Long productId, String name, String barcode, String unit, String category,
                               BigDecimal stock, BigDecimal value) {
    }
}

package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the accountant needs for one month: profit and loss, the sales and purchase books, and the stock value. */
public final class Accounting {

    private Accounting() {
    }

    /** One VAT rate: amount without VAT, VAT, and amount with VAT. */
    public record RateAmount(BigDecimal rate, BigDecimal base, BigDecimal vat, BigDecimal total) {
    }

    /** A line of the profit and loss: a kind of stock loss or an expense category. */
    public record Line(String key, String label, BigDecimal amount, int count) {
    }

    /**
     * Revenue and cost of goods are without VAT (cost too when pos.reports.cost-includes-vat). Stock losses: what left the
     * shelf without being sold (damaged, expired, lost, own use) and the stock count differences, at cost; a negative
     * amount is stock found. Expenses as paid.
     */
    public record ProfitAndLoss(LocalDate from, LocalDate to, long salesCount, BigDecimal grossSales, BigDecimal discounts,
                                BigDecimal refunds, BigDecimal revenue, BigDecimal vat, BigDecimal revenueWithoutVat,
                                BigDecimal costOfGoods, BigDecimal grossProfit, BigDecimal grossMargin,
                                List<Line> stockLosses, BigDecimal stockLossTotal, List<Line> expenses, BigDecimal expenseTotal,
                                BigDecimal netProfit, BigDecimal netMargin) {
    }

    /** This month and the one before, side by side. */
    public record ProfitAndLossCompare(ProfitAndLoss current, ProfitAndLoss previous) {
    }

    /** Sales book: one row per day (receipts minus refunds) by VAT rate. */
    public record SalesDay(LocalDate day, int receipts, String firstInvoice, String lastInvoice, int refunds,
                           List<RateAmount> rates, BigDecimal base, BigDecimal vat, BigDecimal total) {
    }

    /** One receipt or refund of the sales book (refunds are negative). */
    public record SalesDocument(LocalDateTime at, String number, String kind, String customer, String forInvoice,
                                List<RateAmount> rates, BigDecimal base, BigDecimal vat, BigDecimal total) {
    }

    public record SalesBook(LocalDate from, LocalDate to, List<BigDecimal> rates, List<SalesDay> days, List<SalesDocument> documents,
                            List<RateAmount> totals, BigDecimal base, BigDecimal vat, BigDecimal total) {
    }

    /** Purchase book: one row per supplier invoice by VAT rate. */
    public record PurchaseRow(Long id, LocalDate date, String invoice, String supplier, String taxNumber, List<RateAmount> rates,
                              BigDecimal base, BigDecimal vat, BigDecimal total) {
    }

    public record PurchaseBook(LocalDate from, LocalDate to, List<BigDecimal> rates, List<PurchaseRow> invoices,
                               List<RateAmount> totals, BigDecimal base, BigDecimal vat, BigDecimal total) {
    }

    public record StockProduct(Long id, String name, String barcode, String unit, String category, BigDecimal stock,
                               BigDecimal cost, BigDecimal value, BigDecimal valueWithoutVat) {
    }

    public record StockCategory(String category, int products, BigDecimal value, BigDecimal valueWithoutVat) {
    }

    /** Stock and its value at cost at the end of a day (the cost of that moment, from the price history). */
    public record StockValue(LocalDate at, List<StockCategory> categories, List<StockProduct> products, BigDecimal value,
                             BigDecimal valueWithoutVat) {
    }

    /** Everything for one month in one answer. openingStock: stock new products were created with, at cost without VAT. */
    public record Month(String month, ProfitAndLossCompare profitAndLoss, SalesBook sales, PurchaseBook purchases,
                        StockValue stockAtEnd, StockValue stockAtStart, BigDecimal openingStock) {
    }
}

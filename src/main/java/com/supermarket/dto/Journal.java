package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Regjistri i Veprimeve: every operation of the shop in one list. */
public final class Journal {

    private Journal() {
    }

    /** Kinds of operation. */
    public enum Type {
        SALE, REFUND, PURCHASE, ADJUSTMENT, COUNT, CASH_IN, CASH_OUT, SUPPLIER_PAYMENT, DEBT_PAYMENT, SHIFT_OPEN, SHIFT_CLOSE,
        PRICE_CHANGE
    }

    /**
     * One operation. amount is the money it moved (+ in, - out; a stock change is valued at today's cost), null when
     * it moved none. before/after: old and new price for a price change, opening cash and difference for a shift.
     * refId points at the document (sale, refund, purchase invoice, shift, inventory count).
     */
    public record Entry(Type type, LocalDateTime at, String number, String counterparty, Long productId, String productName,
                        BigDecimal quantity, Integer lines, String note, String user, BigDecimal amount, BigDecimal before,
                        BigDecimal after, Long refId) {
    }

    public record TypeTotal(Type type, long count, BigDecimal amount) {
    }

    /** One day of the filtered list: operations, money in and money out. */
    public record DayTotal(LocalDate day, long count, BigDecimal moneyIn, BigDecimal moneyOut) {
    }

    public record Page(List<Entry> entries, long total, int page, int size, List<TypeTotal> totals, List<DayTotal> days) {
    }

    /** What to show. Every field is optional; dates are whole days. */
    public record Filter(LocalDate from, LocalDate to, List<Type> types, Long cashierId, Long productId, String query) {
    }
}

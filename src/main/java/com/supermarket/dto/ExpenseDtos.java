package com.supermarket.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Requests and answers of the expenses screens. */
public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    /** method: CASH (from the till of the user's open shift), BANK or CARD. paidOn: by default today. */
    public record ExpenseRequest(Long categoryId, BigDecimal amount, LocalDate paidOn, String method, String paidTo, String note) {
    }

    public record CategoryRequest(String name, Boolean active) {
    }

    public record CategoryRow(Long id, String name, boolean active, long expenses) {
    }

    public record ExpenseRow(Long id, LocalDate paidOn, Long categoryId, String category, BigDecimal amount, String method,
                             String paidTo, String note, String user, LocalDateTime createdAt, Long shiftId, boolean cancelled,
                             String cancelledBy, LocalDateTime cancelledAt) {
    }

    public record CategoryTotal(Long categoryId, String category, BigDecimal amount, int count) {
    }

    /** Expenses of a period; totals leave out the cancelled ones. */
    public record ExpenseList(LocalDate from, LocalDate to, List<ExpenseRow> expenses, BigDecimal total, BigDecimal cash,
                              BigDecimal bank, BigDecimal card, List<CategoryTotal> byCategory) {
    }
}

package com.supermarket.service;

import com.supermarket.dto.CashMovementRequest;
import com.supermarket.dto.ExpenseDtos.CategoryRequest;
import com.supermarket.dto.ExpenseDtos.CategoryRow;
import com.supermarket.dto.ExpenseDtos.CategoryTotal;
import com.supermarket.dto.ExpenseDtos.ExpenseList;
import com.supermarket.dto.ExpenseDtos.ExpenseRequest;
import com.supermarket.dto.ExpenseDtos.ExpenseRow;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.CashMovement;
import com.supermarket.model.Cashier;
import com.supermarket.model.Expense;
import com.supermarket.model.ExpenseCategory;
import com.supermarket.model.Shift;
import com.supermarket.repository.CashMovementRepository;
import com.supermarket.repository.ExpenseCategoryRepository;
import com.supermarket.repository.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shop expenses. A cash expense is paid from the till of the user's open shift: the expense and a cash-out movement are
 * saved together, so the till still balances at the close. Bank and card expenses are only recorded. Cancelling a cash
 * expense puts the money back into the till, which is only possible while that shift is open.
 */
@Service
public class ExpenseService {

    /** Reason of the till movement of a cash expense; the journal lists the expense itself instead. */
    public static final String CASH_REASON = "Shpenzim / expense: ";
    /** Reason of the till movement that gives back the cash of a cancelled expense. */
    public static final String CANCEL_REASON = "Shpenzim i anuluar / expense cancelled: ";
    static final int MAX_DAYS = 366;

    private final ExpenseRepository expenseRepository;
    private final ExpenseCategoryRepository categoryRepository;
    private final CashMovementRepository cashMovementRepository;
    private final ShiftService shiftService;
    private final AuditService auditService;

    public ExpenseService(ExpenseRepository expenseRepository, ExpenseCategoryRepository categoryRepository,
                          CashMovementRepository cashMovementRepository, ShiftService shiftService, AuditService auditService) {
        this.expenseRepository = expenseRepository;
        this.categoryRepository = categoryRepository;
        this.cashMovementRepository = cashMovementRepository;
        this.shiftService = shiftService;
        this.auditService = auditService;
    }

    /** Cashiers may only pay expenses in cash from their own till; managers also record bank and card payments. */
    @Transactional
    public ExpenseRow add(ExpenseRequest request, Cashier actor, boolean manager) {
        if (request.categoryId() == null) {
            throw new ValidationException("expense.categoryRequired");
        }
        ExpenseCategory category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ValidationException("expense.categoryNotFound", request.categoryId()));
        if (!category.isActive()) {
            throw new ValidationException("expense.categoryInactive", category.getName());
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new ValidationException("payment.amountPositive");
        }
        Expense.Method method;
        try {
            method = Expense.Method.valueOf(request.method() == null ? "CASH" : request.method().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("expense.invalidMethod");
        }
        if (method != Expense.Method.CASH && !manager) {
            throw new PermissionDeniedException("expense.cashOnly");
        }
        LocalDate today = LocalDate.now();
        LocalDate paidOn = method == Expense.Method.CASH || request.paidOn() == null ? today : request.paidOn();
        if (paidOn.isAfter(today)) {
            throw new ValidationException("expense.futureDate");
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String paidTo = blankToNull(request.paidTo(), 150);
        String note = blankToNull(request.note(), 255);

        Expense expense = new Expense(category, amount, paidOn, method, paidTo, note, actor, LocalDateTime.now());
        if (method == Expense.Method.CASH) {
            Shift shift = shiftService.findOpenShift(actor.getId()).orElseThrow(() -> new ValidationException("expense.shiftRequired"));
            CashMovement movement = shiftService.addCashMovement(shift.getId(),
                    new CashMovementRequest("OUT", amount, CASH_REASON + describe(category, paidTo, note)), actor);
            expense.paidFromTill(shift.getId(), movement.getId());
        }
        Expense saved = expenseRepository.save(expense);
        auditService.record(actor, "EXPENSE_ADDED", "EXPENSE", saved.getId(),
                category.getName() + ": " + amount + " LEK " + method + (paidTo == null ? "" : " - " + paidTo));
        return row(saved);
    }

    @Transactional
    public ExpenseRow cancel(Long id, Cashier actor) {
        Expense expense = expenseRepository.findById(id).orElseThrow(() -> new ValidationException("expense.notFound", id));
        if (expense.isCancelled()) {
            throw new ValidationException("expense.alreadyCancelled");
        }
        if (expense.getShiftId() != null) {
            Shift shift = shiftService.findById(expense.getShiftId());
            if (!"OPEN".equals(shift.getStatus())) {
                throw new ValidationException("expense.shiftClosed");
            }
            // The money goes back into the same till, whoever cancels it.
            cashMovementRepository.save(new CashMovement(shift, actor, CashMovement.Type.IN, expense.getAmount(),
                    CANCEL_REASON + describe(expense.getCategory(), expense.getPaidTo(), expense.getNote()), LocalDateTime.now()));
        }
        expense.cancel(actor, LocalDateTime.now());
        auditService.record(actor, "EXPENSE_CANCELLED", "EXPENSE", id,
                expense.getCategory().getName() + ": " + expense.getAmount() + " LEK " + expense.getMethod());
        return row(expense);
    }

    @Transactional(readOnly = true)
    public ExpenseList list(LocalDate from, LocalDate to, Long categoryId, boolean withCancelled) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            throw new ValidationException("journal.invalidPeriod");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new ValidationException("journal.periodTooLong", MAX_DAYS);
        }
        List<Expense> expenses = expenseRepository.findPaidBetween(start, end).stream()
                .filter(e -> categoryId == null || e.getCategory().getId().equals(categoryId))
                .toList();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> byMethod = new HashMap<>();
        Map<Long, CategoryTotal> byCategory = new LinkedHashMap<>();
        for (Expense e : expenses) {
            if (e.isCancelled()) {
                continue;
            }
            total = total.add(e.getAmount());
            byMethod.merge(e.getMethod(), e.getAmount(), BigDecimal::add);
            byCategory.merge(e.getCategory().getId(), new CategoryTotal(e.getCategory().getId(), e.getCategory().getName(), e.getAmount(), 1),
                    (a, b) -> new CategoryTotal(a.categoryId(), a.category(), a.amount().add(b.amount()), a.count() + 1));
        }
        List<CategoryTotal> categories = new ArrayList<>(byCategory.values());
        categories.sort(Comparator.comparing(CategoryTotal::amount).reversed());
        return new ExpenseList(start, end,
                expenses.stream().filter(e -> withCancelled || !e.isCancelled()).map(ExpenseService::row).toList(),
                total, byMethod.getOrDefault("CASH", BigDecimal.ZERO), byMethod.getOrDefault("BANK", BigDecimal.ZERO),
                byMethod.getOrDefault("CARD", BigDecimal.ZERO), categories);
    }

    @Transactional(readOnly = true)
    public List<CategoryRow> categories(boolean activeOnly) {
        Map<Long, Long> counts = new HashMap<>();
        expenseRepository.countByCategory().forEach(r -> counts.put((Long) r[0], ((Number) r[1]).longValue()));
        return categoryRepository.findAllByOrderByNameAsc().stream()
                .filter(c -> !activeOnly || c.isActive())
                .map(c -> new CategoryRow(c.getId(), c.getName(), c.isActive(), counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryRow saveCategory(Long id, CategoryRequest request, Cashier actor) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ValidationException("expense.categoryNameRequired");
        }
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        ExpenseCategory category;
        if (id == null) {
            if (categoryRepository.existsByNameIgnoreCase(name)) {
                throw new ValidationException("expense.categoryExists", name);
            }
            category = new ExpenseCategory(name, LocalDateTime.now());
        } else {
            category = categoryRepository.findById(id).orElseThrow(() -> new ValidationException("expense.categoryNotFound", id));
            if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
                throw new ValidationException("expense.categoryExists", name);
            }
            category.setName(name);
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        ExpenseCategory saved = categoryRepository.save(category);
        auditService.record(actor, id == null ? "EXPENSE_CATEGORY_ADDED" : "EXPENSE_CATEGORY_CHANGED", "EXPENSE_CATEGORY", saved.getId(),
                saved.getName() + (saved.isActive() ? "" : " (inactive)"));
        return new CategoryRow(saved.getId(), saved.getName(), saved.isActive(), 0);
    }

    static ExpenseRow row(Expense e) {
        return new ExpenseRow(e.getId(), e.getPaidOn(), e.getCategory().getId(), e.getCategory().getName(), e.getAmount(), e.getMethod(),
                e.getPaidTo(), e.getNote(), e.getCashier().getFullName(), e.getCreatedAt(), e.getShiftId(), e.isCancelled(),
                e.getCancelledBy() == null ? null : e.getCancelledBy().getFullName(), e.getCancelledAt());
    }

    private static String describe(ExpenseCategory category, String paidTo, String note) {
        StringBuilder text = new StringBuilder(category.getName());
        if (paidTo != null) {
            text.append(" - ").append(paidTo);
        }
        if (note != null) {
            text.append(" - ").append(note);
        }
        return text.length() > 200 ? text.substring(0, 200) : text.toString();
    }

    private static String blankToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}

package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ExpenseDtos.CategoryRequest;
import com.supermarket.dto.ExpenseDtos.CategoryRow;
import com.supermarket.dto.ExpenseDtos.ExpenseList;
import com.supermarket.dto.ExpenseDtos.ExpenseRequest;
import com.supermarket.dto.ExpenseDtos.ExpenseRow;
import com.supermarket.model.Cashier;
import com.supermarket.service.ExpenseService;
import com.supermarket.service.ExportService;
import com.supermarket.service.SessionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Shpenzimet: every till can pay an expense in cash; managers see, record and cancel all of them. */
@RestController
@RequestMapping("/expenses")
public class ExpenseController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ExpenseService expenseService;
    private final SessionService sessionService;
    private final ExportService exportService;
    private final MessageSource messageSource;

    public ExpenseController(ExpenseService expenseService, SessionService sessionService, ExportService exportService,
                             MessageSource messageSource) {
        this.expenseService = expenseService;
        this.sessionService = sessionService;
        this.exportService = exportService;
        this.messageSource = messageSource;
    }

    /** from/to: by default this month. */
    @GetMapping
    public ApiResponse<ExpenseList> list(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(defaultValue = "true") boolean withCancelled) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Expenses", expenseService.list(from, to, categoryId, withCancelled));
    }

    @PostMapping
    public ApiResponse<ExpenseRow> add(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                       @RequestBody ExpenseRequest request) {
        Cashier actor = sessionService.requireUser(token);
        return ApiResponse.ok("Expense saved", expenseService.add(request, actor, actor.getRole().isOperationalManager()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ExpenseRow> cancel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                          @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Expense cancelled", expenseService.cancel(id, actor));
    }

    /** Every till needs the active categories to pay an expense in cash. */
    @GetMapping("/categories")
    public ApiResponse<List<CategoryRow>> categories(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                     @RequestParam(defaultValue = "true") boolean activeOnly) {
        Cashier actor = sessionService.requireUser(token);
        return ApiResponse.ok("Categories", expenseService.categories(activeOnly || !actor.getRole().isOperationalManager()));
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryRow> addCategory(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                @RequestBody CategoryRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Category saved", expenseService.saveCategory(null, request, actor));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<CategoryRow> updateCategory(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                   @PathVariable Long id, @RequestBody CategoryRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Category saved", expenseService.saveCategory(id, request, actor));
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> excel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to,
                                        @RequestParam(required = false) Long categoryId) {
        sessionService.requireOperationalManager(token);
        ExpenseList list = expenseService.list(from, to, categoryId, false);
        List<Object[]> rows = list.expenses().stream()
                .map(e -> new Object[] {e.paidOn().format(DATE), e.category(), e.paidTo(), e.note(), text("expense.method." + e.method()),
                        e.user(), e.amount()})
                .toList();
        byte[] file = exportService.tableXlsx(text("expense.title"), List.of(text("export.date"), text("expense.category"),
                text("expense.paidTo"), text("export.details"), text("expense.method"), text("export.user"), text("export.amount")), rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shpenzimet-" + list.from() + "-" + list.to() + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    private String text(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }
}

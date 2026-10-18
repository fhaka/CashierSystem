package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.Journal;
import com.supermarket.exception.ValidationException;
import com.supermarket.repository.CashierRepository;
import com.supermarket.service.ExportService;
import com.supermarket.service.JournalService;
import com.supermarket.service.SessionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Regjistri i Veprimeve: every operation of the shop, for managers. */
@RestController
@RequestMapping("/journal")
public class JournalController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SessionService sessionService;
    private final JournalService journalService;
    private final ExportService exportService;
    private final CashierRepository cashierRepository;
    private final MessageSource messageSource;

    public JournalController(SessionService sessionService, JournalService journalService, ExportService exportService,
                             CashierRepository cashierRepository, MessageSource messageSource) {
        this.sessionService = sessionService;
        this.journalService = journalService;
        this.exportService = exportService;
        this.cashierRepository = cashierRepository;
        this.messageSource = messageSource;
    }

    /** types: comma-separated (SALE,REFUND,...); from/to: yyyy-MM-dd, by default the last 7 days. */
    @GetMapping
    public ApiResponse<Journal.Page> page(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to,
                                          @RequestParam(required = false) String types,
                                          @RequestParam(required = false) Long cashierId,
                                          @RequestParam(required = false) Long productId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "100") int size) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Journal", journalService.page(filter(from, to, types, cashierId, productId, q), page, size));
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> users(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Users", cashierRepository.findAll().stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getFullName()))
                .toList());
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> excel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to,
                                        @RequestParam(required = false) String types,
                                        @RequestParam(required = false) Long cashierId,
                                        @RequestParam(required = false) Long productId,
                                        @RequestParam(required = false) String q) {
        sessionService.requireOperationalManager(token);
        List<Object[]> rows = journalService.entries(filter(from, to, types, cashierId, productId, q)).stream()
                .map(e -> new Object[] {e.at().format(DATE_TIME), text("journal.type." + e.type()), e.number(), e.counterparty(),
                        e.productName(), e.quantity(), e.lines(), e.note(), e.user(), e.amount()})
                .toList();
        byte[] file = exportService.tableXlsx(text("journal.title"), List.of(text("export.date"), text("export.type"),
                text("export.document"), text("journal.counterparty"), text("export.product"), text("export.quantity"),
                text("export.lines"), text("export.details"), text("export.user"), text("export.amount")), rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"regjistri-i-veprimeve.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    private static Journal.Filter filter(LocalDate from, LocalDate to, String types, Long cashierId, Long productId, String q) {
        List<Journal.Type> kinds = types == null || types.isBlank() ? List.of() : Arrays.stream(types.split(","))
                .map(String::trim).filter(t -> !t.isEmpty())
                .map(t -> {
                    try {
                        return Journal.Type.valueOf(t.toUpperCase());
                    } catch (IllegalArgumentException exception) {
                        throw new ValidationException("journal.invalidType", t);
                    }
                })
                .toList();
        return new Journal.Filter(from, to, kinds, cashierId, productId, q);
    }

    private String text(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }
}

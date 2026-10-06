package com.supermarket.controller;

import com.supermarket.dto.Analytics;
import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.PeriodReport;
import com.supermarket.dto.SalesPage;
import com.supermarket.model.Cashier;
import com.supermarket.repository.ProductRepository;
import com.supermarket.service.AnalyticsService;
import com.supermarket.service.ExportService;
import com.supermarket.service.ReportMailer;
import com.supermarket.service.ReportService;
import com.supermarket.service.SaleService;
import com.supermarket.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports and their exports. Dates are yyyy-MM-dd; by default the current month. */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SaleService saleService;
    private final SessionService sessionService;
    private final ReportService reportService;
    private final AnalyticsService analyticsService;
    private final ExportService exportService;
    private final ReportMailer reportMailer;
    private final ProductRepository productRepository;

    public ReportController(SaleService saleService, SessionService sessionService, ReportService reportService,
                            AnalyticsService analyticsService, ExportService exportService, ReportMailer reportMailer,
                            ProductRepository productRepository) {
        this.saleService = saleService;
        this.sessionService = sessionService;
        this.reportService = reportService;
        this.analyticsService = analyticsService;
        this.exportService = exportService;
        this.reportMailer = reportMailer;
        this.productRepository = productRepository;
    }

    /** Today's numbers for the home screen. Cashiers see their own sales. */
    @GetMapping("/dashboard")
    public ApiResponse<SalesPage.Dashboard> dashboard(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Dashboard loaded", saleService.dashboard(
                cashier.getRole().isOperationalManager() ? null : cashier.getId(),
                productRepository.countByActiveTrue(), productRepository.countLowStock()));
    }

    @GetMapping("/analytics")
    public ApiResponse<Analytics> analytics(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                            @RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Analytics loaded", analyticsService.analyze(start(from), end(to)));
    }

    @GetMapping("/analytics.xlsx")
    public ResponseEntity<byte[]> analyticsExcel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                 @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        sessionService.requireOperationalManager(token);
        Analytics analytics = analyticsService.analyze(start(from), end(to));
        return file(exportService.analyticsXlsx(analytics), XLSX, "raporti-" + analytics.from() + "-" + analytics.to() + ".xlsx");
    }

    @GetMapping("/analytics.pdf")
    public ResponseEntity<byte[]> analyticsPdf(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to) {
        sessionService.requireOperationalManager(token);
        Analytics analytics = analyticsService.analyze(start(from), end(to));
        return file(exportService.analyticsPdf(analytics), MediaType.APPLICATION_PDF,
                "raporti-" + analytics.from() + "-" + analytics.to() + ".pdf");
    }

    /** Every sale of the period, one row each. */
    @GetMapping("/sales.xlsx")
    public ResponseEntity<byte[]> salesExcel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        sessionService.requireOperationalManager(token);
        LocalDate first = start(from);
        LocalDate last = end(to);
        List<SalesPage.Row> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            SalesPage result = saleService.search(first, last, null, null, null, page, 200);
            rows.addAll(result.rows());
            if ((long) (page + 1) * result.size() >= result.totalCount()) {
                break;
            }
        }
        return file(exportService.salesXlsx(rows, first, last), XLSX, "shitjet-" + first + "-" + last + ".xlsx");
    }

    /** Z report: everything sold and received on one day, on all tills. date as yyyy-MM-dd, today if empty. */
    @GetMapping("/daily")
    public ApiResponse<PeriodReport> dailyReport(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestParam(required = false) LocalDate date
    ) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Daily report loaded", reportService.dailyReport(date == null ? LocalDate.now() : date));
    }

    @GetMapping("/daily.pdf")
    public ResponseEntity<byte[]> dailyReportPdf(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                 @RequestParam(required = false) LocalDate date) {
        sessionService.requireOperationalManager(token);
        LocalDate day = date == null ? LocalDate.now() : date;
        return file(exportService.textPdf(reportService.dailyReport(day).printableText()), MediaType.APPLICATION_PDF,
                "raporti-z-" + day + ".pdf");
    }

    /** Sends the Z report of a day by email now (to test the email settings). */
    @PostMapping("/daily/email")
    public ApiResponse<Map<String, Object>> emailDailyReport(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                             @RequestParam(required = false) LocalDate date) {
        sessionService.requireOperationalManager(token);
        List<String> sentTo = reportMailer.sendDailyReport(date == null ? LocalDate.now() : date);
        return ApiResponse.ok("Report sent", Map.of("sentTo", sentTo));
    }

    @GetMapping("/email-settings")
    public ApiResponse<Map<String, Boolean>> emailSettings(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Email settings", Map.of("configured", reportMailer.isConfigured()));
    }

    private static LocalDate start(LocalDate from) {
        return from == null ? LocalDate.now().withDayOfMonth(1) : from;
    }

    private static LocalDate end(LocalDate to) {
        return to == null ? LocalDate.now() : to;
    }

    private static ResponseEntity<byte[]> file(byte[] content, MediaType type, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(type)
                .body(content);
    }
}

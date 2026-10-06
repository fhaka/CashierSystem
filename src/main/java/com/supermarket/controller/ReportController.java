package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.PeriodReport;
import com.supermarket.model.Sale;
import com.supermarket.service.ReportService;
import com.supermarket.service.SaleService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reports")
public class ReportController {

    private final SaleService saleService;
    private final SessionService sessionService;
    private final ReportService reportService;

    public ReportController(SaleService saleService, SessionService sessionService, ReportService reportService) {
        this.saleService = saleService;
        this.sessionService = sessionService;
        this.reportService = reportService;
    }

    @GetMapping("/sales")
    public ApiResponse<List<Sale>> salesReport(
            @RequestHeader(value = "X-Auth-Token", required = false) String token
    ) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Sales report loaded", saleService.findAll());
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
}

package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.model.Sale;
import com.supermarket.service.SaleService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/reports")
public class ReportController {

    private final SaleService saleService;
    private final SessionService sessionService;

    public ReportController(SaleService saleService, SessionService sessionService) {
        this.saleService = saleService;
        this.sessionService = sessionService;
    }

    @GetMapping("/sales")
    public ApiResponse<List<Sale>> salesReport(
            @RequestHeader(value = "X-Auth-Token", required = false) String token
    ) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Sales report loaded", saleService.findAll());
    }
}

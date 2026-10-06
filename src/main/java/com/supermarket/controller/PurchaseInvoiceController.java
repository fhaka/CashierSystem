package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.PurchaseInvoiceRequest;
import com.supermarket.model.PurchaseInvoice;
import com.supermarket.service.PurchaseInvoiceService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@RestController
@RequestMapping("/purchases")
public class PurchaseInvoiceController {

    private final PurchaseInvoiceService purchaseInvoiceService;
    private final SessionService sessionService;

    public PurchaseInvoiceController(PurchaseInvoiceService purchaseInvoiceService, SessionService sessionService) {
        this.purchaseInvoiceService = purchaseInvoiceService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<PurchaseInvoice>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Purchase invoices loaded", purchaseInvoiceService.findAll());
    }

    @PostMapping
    public ApiResponse<PurchaseInvoice> create(@RequestHeader(value = "X-Auth-Token", required = false) String token, @RequestBody PurchaseInvoiceRequest request) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Purchase invoice saved", purchaseInvoiceService.create(request));
    }
}

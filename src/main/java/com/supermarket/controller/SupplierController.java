package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.SupplierDtos.PaymentRequest;
import com.supermarket.dto.SupplierDtos.SupplierDetail;
import com.supermarket.dto.SupplierDtos.SupplierRequest;
import com.supermarket.dto.SupplierDtos.SupplierSummary;
import com.supermarket.model.Cashier;
import com.supermarket.model.SupplierPayment;
import com.supermarket.service.SessionService;
import com.supermarket.service.SupplierService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/suppliers")
public class SupplierController {

    private final SupplierService supplierService;
    private final SessionService sessionService;

    public SupplierController(SupplierService supplierService, SessionService sessionService) {
        this.supplierService = supplierService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<SupplierSummary>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Suppliers loaded", supplierService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierDetail> detail(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Supplier loaded", supplierService.detail(id));
    }

    @PostMapping
    public ApiResponse<SupplierSummary> create(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                               @RequestBody SupplierRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Supplier created", supplierService.create(request, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierSummary> update(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                               @PathVariable Long id, @RequestBody SupplierRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Supplier updated", supplierService.update(id, request, actor));
    }

    @PostMapping("/{id}/payments")
    public ApiResponse<SupplierPayment> addPayment(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                   @PathVariable Long id, @RequestBody PaymentRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Supplier payment saved", supplierService.addPayment(id, request, actor));
    }
}

package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.RefundRequest;
import com.supermarket.dto.RefundResponse;
import com.supermarket.dto.SaleForRefund;
import com.supermarket.model.Cashier;
import com.supermarket.service.RefundService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Refunds. Any till can look up a sale by its invoice number; a cashier needs a manager's PIN to refund. */
@RestController
@RequestMapping("/sales")
public class RefundController {

    private final RefundService refundService;
    private final SessionService sessionService;

    public RefundController(RefundService refundService, SessionService sessionService) {
        this.refundService = refundService;
        this.sessionService = sessionService;
    }

    @GetMapping("/by-invoice/{invoiceNumber}")
    public ApiResponse<SaleForRefund> findByInvoice(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @PathVariable String invoiceNumber
    ) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Sale loaded", refundService.findSaleForRefund(invoiceNumber));
    }

    @PostMapping("/{saleId}/refunds")
    public ApiResponse<RefundResponse> refund(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestHeader(value = "X-Approval-Pin", required = false) String approvalPin,
            @PathVariable Long saleId,
            @RequestBody RefundRequest request
    ) {
        Cashier cashier = sessionService.requireUser(token);
        return ApiResponse.ok("Refund completed", refundService.refund(cashier, saleId, request, approvalPin));
    }

    @GetMapping("/{saleId}/refunds")
    public ApiResponse<List<RefundResponse>> findRefunds(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @PathVariable Long saleId
    ) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Refunds loaded", refundService.findForSale(saleId));
    }
}

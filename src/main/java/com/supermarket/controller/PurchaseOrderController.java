package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.OrderDtos.OrderRequest;
import com.supermarket.dto.OrderDtos.OrderRow;
import com.supermarket.model.Cashier;
import com.supermarket.model.PurchaseOrder;
import com.supermarket.service.PurchaseOrderService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Porositë te furnitorët, for managers. */
@RestController
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService orderService;
    private final SessionService sessionService;

    public PurchaseOrderController(PurchaseOrderService orderService, SessionService sessionService) {
        this.orderService = orderService;
        this.sessionService = sessionService;
    }

    /** status: OPEN, SENT, RECEIVED, CANCELLED or ACTIVE (open and sent); empty for all. */
    @GetMapping
    public ApiResponse<List<OrderRow>> list(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                            @RequestParam(required = false) String status) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Orders", orderService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderRow> get(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Order", orderService.get(id));
    }

    @PostMapping
    public ApiResponse<OrderRow> create(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @RequestBody OrderRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Order saved", orderService.create(request, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<OrderRow> update(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @PathVariable Long id, @RequestBody OrderRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Order saved", orderService.update(id, request, actor));
    }

    @PostMapping("/{id}/sent")
    public ApiResponse<OrderRow> sent(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Order sent", orderService.markSent(id, actor));
    }

    @PostMapping("/{id}/received")
    public ApiResponse<OrderRow> received(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Order received", orderService.close(id, PurchaseOrder.Status.RECEIVED, actor));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderRow> cancel(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Order cancelled", orderService.close(id, PurchaseOrder.Status.CANCELLED, actor));
    }
}

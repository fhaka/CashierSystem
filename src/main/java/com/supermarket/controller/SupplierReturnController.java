package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ReturnDtos.ReturnRequest;
import com.supermarket.dto.ReturnDtos.ReturnRow;
import com.supermarket.model.Cashier;
import com.supermarket.service.SessionService;
import com.supermarket.service.SupplierReturnService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Kthimet te furnitorët, for managers. */
@RestController
@RequestMapping("/supplier-returns")
public class SupplierReturnController {

    private final SupplierReturnService returnService;
    private final SessionService sessionService;

    public SupplierReturnController(SupplierReturnService returnService, SessionService sessionService) {
        this.returnService = returnService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<ReturnRow>> list(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                             @RequestParam(defaultValue = "false") boolean pending) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Returns", returnService.list(pending));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReturnRow> get(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Return", returnService.get(id));
    }

    @PostMapping
    public ApiResponse<ReturnRow> create(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestBody ReturnRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Return saved", returnService.create(request, actor));
    }

    @PostMapping("/{id}/replaced")
    public ApiResponse<ReturnRow> replaced(@RequestHeader(value = "X-Auth-Token", required = false) String token, @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Replacement received", returnService.replaced(id, actor));
    }
}

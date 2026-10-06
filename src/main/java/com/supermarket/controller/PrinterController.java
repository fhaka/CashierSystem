package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.PrintReceiptRequest;
import com.supermarket.service.ThermalPrinterService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@RestController
@RequestMapping("/printer")
public class PrinterController {

    private final ThermalPrinterService thermalPrinterService;
    private final SessionService sessionService;

    public PrinterController(ThermalPrinterService thermalPrinterService, SessionService sessionService) {
        this.thermalPrinterService = thermalPrinterService;
        this.sessionService = sessionService;
    }

    @GetMapping("/printers")
    public ApiResponse<List<String>> findPrinters(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Printers loaded", thermalPrinterService.findPrinterNames());
    }

    @PostMapping("/receipt")
    public ApiResponse<Void> printReceipt(@RequestHeader(value = "X-Auth-Token", required = false) String token, @RequestBody PrintReceiptRequest request) {
        sessionService.requireUser(token);
        thermalPrinterService.printReceipt(request.getReceiptText(), request.getPrinterName());
        return ApiResponse.ok("Receipt sent to thermal printer", null);
    }

    @PostMapping("/test-cut")
    public ApiResponse<Void> testCut(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireSuperAdmin(token);
        thermalPrinterService.testCut();
        return ApiResponse.ok("Cut test sent to thermal printer", null);
    }
}

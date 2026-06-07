package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.PrintReceiptRequest;
import com.supermarket.service.ThermalPrinterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/printer")
public class PrinterController {

    private final ThermalPrinterService thermalPrinterService;

    public PrinterController(ThermalPrinterService thermalPrinterService) {
        this.thermalPrinterService = thermalPrinterService;
    }

    @GetMapping("/printers")
    public ApiResponse<List<String>> findPrinters() {
        return ApiResponse.ok("Printers loaded", thermalPrinterService.findPrinterNames());
    }

    @PostMapping("/receipt")
    public ApiResponse<Void> printReceipt(@RequestBody PrintReceiptRequest request) {
        thermalPrinterService.printReceipt(request.getReceiptText());
        return ApiResponse.ok("Receipt sent to thermal printer", null);
    }

    @PostMapping("/test-cut")
    public ApiResponse<Void> testCut() {
        thermalPrinterService.testCut();
        return ApiResponse.ok("Cut test sent to thermal printer", null);
    }
}

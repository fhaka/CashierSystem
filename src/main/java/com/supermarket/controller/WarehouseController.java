package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ShopSettings;
import com.supermarket.dto.Warehouse;
import com.supermarket.model.Cashier;
import com.supermarket.model.ProductPackage;
import com.supermarket.service.PackageService;
import com.supermarket.service.SessionService;
import com.supermarket.service.ShopSettingsService;
import com.supermarket.service.ThermalPrinterService;
import com.supermarket.service.WarehouseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The "Magazina" page: stock overview, products, product card, boxes, bulk prices and shelf labels (managers). */
@RestController
@RequestMapping("/warehouse")
public class WarehouseController {

    private final SessionService sessionService;
    private final WarehouseService warehouseService;
    private final PackageService packageService;
    private final ThermalPrinterService printerService;
    private final ShopSettingsService shopSettingsService;

    public WarehouseController(SessionService sessionService, WarehouseService warehouseService, PackageService packageService,
                               ThermalPrinterService printerService, ShopSettingsService shopSettingsService) {
        this.sessionService = sessionService;
        this.warehouseService = warehouseService;
        this.packageService = packageService;
        this.printerService = printerService;
        this.shopSettingsService = shopSettingsService;
    }

    @GetMapping("/overview")
    public ApiResponse<Warehouse.Overview> overview(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Warehouse overview", warehouseService.overview());
    }

    @GetMapping("/products")
    public ApiResponse<List<Warehouse.ProductRow>> products(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Warehouse products", warehouseService.products());
    }

    @GetMapping("/products/{id}")
    public ApiResponse<Warehouse.ProductCard> card(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                   @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Product card", warehouseService.card(id));
    }

    @PostMapping("/products/{id}/packages")
    public ApiResponse<ProductPackage> createPackage(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                     @PathVariable Long id, @RequestBody PackageService.PackageRequest request) {
        Cashier manager = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Box saved", packageService.create(id, request, manager));
    }

    @PutMapping("/packages/{id}")
    public ApiResponse<ProductPackage> updatePackage(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                     @PathVariable Long id, @RequestBody PackageService.PackageRequest request) {
        Cashier manager = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Box saved", packageService.update(id, request, manager));
    }

    /** With "apply": false only shows the new prices; with true saves them. */
    @PostMapping("/prices/bulk")
    public ApiResponse<List<Warehouse.BulkPriceRow>> bulkPrices(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                                @RequestBody Warehouse.BulkPriceRequest request) {
        Cashier manager = sessionService.requireOperationalManager(token);
        return ApiResponse.ok(request.apply() ? "Prices changed" : "Price preview", warehouseService.bulkPrices(request, manager));
    }

    /** Products whose selling price changed today: their shelf labels are out of date. */
    @GetMapping("/labels/changed-today")
    public ApiResponse<List<Long>> changedToday(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Changed prices", warehouseService.productsWithNewPriceToday());
    }

    @PostMapping("/labels/preview")
    public ApiResponse<List<Warehouse.Label>> previewLabels(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                            @RequestBody Warehouse.LabelRequest request) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Labels", warehouseService.labels(request.items()));
    }

    /** Prints on the given printer, or the shop's receipt printer from Settings. */
    @PostMapping("/labels/print")
    public ApiResponse<Map<String, Integer>> printLabels(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                        @RequestBody Warehouse.LabelRequest request) {
        sessionService.requireOperationalManager(token);
        List<Warehouse.Label> labels = warehouseService.labels(request.items());
        ShopSettings shop = shopSettingsService.get();
        String printer = request.printerName() == null || request.printerName().isBlank() ? shop.receiptPrinter() : request.printerName();
        printerService.printLabels(labels, printer, shop.name(), shop.receiptWidth());
        return ApiResponse.ok("Labels printed", Map.of("labels", labels.stream().mapToInt(Warehouse.Label::copies).sum()));
    }
}

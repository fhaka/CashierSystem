package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.InventoryDtos.AdjustmentRequest;
import com.supermarket.dto.InventoryDtos.CountLineRequest;
import com.supermarket.dto.InventoryDtos.CountView;
import com.supermarket.dto.InventoryDtos.ExpiringLine;
import com.supermarket.dto.InventoryDtos.ReorderGroup;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.model.StockAdjustment;
import com.supermarket.service.InventoryCountService;
import com.supermarket.service.SessionService;
import com.supermarket.service.StockService;
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
import java.util.Map;

/** Stock outside of the till: low stock, reorder list, expiry dates, manual adjustments and stock counts. */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final StockService stockService;
    private final InventoryCountService countService;
    private final SessionService sessionService;

    public InventoryController(StockService stockService, InventoryCountService countService, SessionService sessionService) {
        this.stockService = stockService;
        this.countService = countService;
        this.sessionService = sessionService;
    }

    @GetMapping("/low-stock")
    public ApiResponse<List<Product>> lowStock(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Low stock loaded", stockService.lowStock());
    }

    @GetMapping("/reorder")
    public ApiResponse<List<ReorderGroup>> reorder(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Reorder list loaded", stockService.reorderList());
    }

    @GetMapping("/expiring")
    public ApiResponse<List<ExpiringLine>> expiring(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                    @RequestParam(defaultValue = "7") int days) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Expiring products loaded", stockService.expiring(days));
    }

    @PostMapping("/adjustments")
    public ApiResponse<StockAdjustment> adjust(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @RequestBody AdjustmentRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Stock adjusted", stockService.adjust(request, actor));
    }

    @GetMapping("/adjustments")
    public ApiResponse<List<StockAdjustment>> history(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                     @RequestParam Long productId) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Stock history loaded", stockService.history(productId));
    }

    /** The stock count in progress, or null. */
    @GetMapping("/counts/current")
    public ApiResponse<CountView> currentCount(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Current count loaded", countService.current());
    }

    @PostMapping("/counts")
    public ApiResponse<CountView> startCount(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                             @RequestBody(required = false) Map<String, String> body) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Count started", countService.start(actor, body == null ? null : body.get("note")));
    }

    @GetMapping("/counts/{id}")
    public ApiResponse<CountView> findCount(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                            @PathVariable Long id) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Count loaded", countService.find(id));
    }

    @PutMapping("/counts/{id}/lines")
    public ApiResponse<CountView> recordLine(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                             @PathVariable Long id, @RequestBody CountLineRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Count line saved", countService.record(id, request, actor));
    }

    @PostMapping("/counts/{id}/apply")
    public ApiResponse<CountView> applyCount(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                             @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Count applied", countService.apply(id, actor));
    }

    @PostMapping("/counts/{id}/cancel")
    public ApiResponse<CountView> cancelCount(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @PathVariable Long id) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Count cancelled", countService.cancel(id, actor));
    }
}

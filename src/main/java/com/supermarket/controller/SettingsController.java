package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ShopSettings;
import com.supermarket.model.Cashier;
import com.supermarket.service.SessionService;
import com.supermarket.service.ShopSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Shop details and receipt options. Everyone can read them (the till needs them); only the administrator edits. */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final SessionService sessionService;
    private final ShopSettingsService shopSettingsService;

    public SettingsController(SessionService sessionService, ShopSettingsService shopSettingsService) {
        this.sessionService = sessionService;
        this.shopSettingsService = shopSettingsService;
    }

    @GetMapping("/shop")
    public ApiResponse<ShopSettings> shop(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Settings loaded", shopSettingsService.get());
    }

    @PutMapping("/shop")
    public ApiResponse<ShopSettings> update(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                           @RequestBody ShopSettings settings) {
        Cashier admin = sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("Settings saved", shopSettingsService.update(settings, admin));
    }

    /** How a receipt looks with these settings, without saving them. */
    @PostMapping("/shop/preview")
    public ApiResponse<Map<String, String>> preview(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                   @RequestBody ShopSettings settings) {
        sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("Preview", Map.of("receiptText", shopSettingsService.preview(settings)));
    }
}

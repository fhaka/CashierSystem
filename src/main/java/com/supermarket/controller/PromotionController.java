package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.model.Cashier;
import com.supermarket.model.Promotion;
import com.supermarket.service.PromotionService;
import com.supermarket.service.SessionService;
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
@RequestMapping("/promotions")
public class PromotionController {

    private final PromotionService promotionService;
    private final SessionService sessionService;

    public PromotionController(PromotionService promotionService, SessionService sessionService) {
        this.promotionService = promotionService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<Promotion>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Promotions loaded", promotionService.findAll());
    }

    @PostMapping
    public ApiResponse<Promotion> create(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestBody PromotionService.PromotionRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Promotion created", promotionService.create(request, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<Promotion> update(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @PathVariable Long id, @RequestBody PromotionService.PromotionRequest request) {
        Cashier actor = sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Promotion updated", promotionService.update(id, request, actor));
    }
}

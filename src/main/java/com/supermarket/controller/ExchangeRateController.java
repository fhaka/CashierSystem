package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.ExchangeRateRequest;
import com.supermarket.model.ExchangeRate;
import com.supermarket.service.ExchangeRateService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;
    private final SessionService sessionService;

    public ExchangeRateController(ExchangeRateService exchangeRateService, SessionService sessionService) {
        this.exchangeRateService = exchangeRateService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<ExchangeRate>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Exchange rates loaded", exchangeRateService.findAll());
    }

    @PutMapping
    public ApiResponse<List<ExchangeRate>> update(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestBody List<ExchangeRateRequest> rates
    ) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Exchange rates saved", exchangeRateService.update(rates));
    }
}

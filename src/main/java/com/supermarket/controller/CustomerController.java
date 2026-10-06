package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.CustomerDtos.CustomerDetail;
import com.supermarket.dto.CustomerDtos.CustomerPaymentRequest;
import com.supermarket.dto.CustomerDtos.CustomerRequest;
import com.supermarket.model.Cashier;
import com.supermarket.model.Customer;
import com.supermarket.service.CustomerService;
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

/**
 * Customers. Every till can find, register and edit customers and take debt payments;
 * only managers change credit limits.
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final SessionService sessionService;

    public CustomerController(CustomerService customerService, SessionService sessionService) {
        this.customerService = customerService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<Customer>> search(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @RequestParam(required = false) String query) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Customers loaded", customerService.search(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerDetail> detail(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @PathVariable Long id) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Customer loaded", customerService.detail(id));
    }

    @PostMapping
    public ApiResponse<Customer> create(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @RequestBody CustomerRequest request) {
        Cashier actor = sessionService.requireUser(token);
        return ApiResponse.ok("Customer created", customerService.create(request, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<Customer> update(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @PathVariable Long id, @RequestBody CustomerRequest request) {
        Cashier actor = sessionService.requireUser(token);
        return ApiResponse.ok("Customer updated", customerService.update(id, request, actor));
    }

    @PostMapping("/{id}/payments")
    public ApiResponse<Customer> receivePayment(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                @PathVariable Long id, @RequestBody CustomerPaymentRequest request) {
        Cashier actor = sessionService.requireUser(token);
        return ApiResponse.ok("Payment received", customerService.receivePayment(id, request, actor));
    }
}

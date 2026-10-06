package com.supermarket.dto;

import com.supermarket.model.Customer;
import com.supermarket.model.CustomerTransaction;

import java.math.BigDecimal;
import java.util.List;

/** Requests and answers of the customer screens. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /** cardNumber empty: a number is generated. creditLimit is only changed by managers. */
    public record CustomerRequest(String cardNumber, String fullName, String phone, String email, String notes,
                                  BigDecimal creditLimit, Boolean active) {
    }

    /** method is CASH (goes into the drawer of the current shift) or CARD. */
    public record CustomerPaymentRequest(BigDecimal amount, String method, String note) {
    }

    public record CustomerDetail(Customer customer, List<CustomerTransaction> transactions) {
    }
}

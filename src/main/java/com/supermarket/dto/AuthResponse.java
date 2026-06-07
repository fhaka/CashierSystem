package com.supermarket.dto;

public class AuthResponse {

    private Long cashierId;
    private String fullName;
    private String username;

    public AuthResponse(Long cashierId, String fullName, String username) {
        this.cashierId = cashierId;
        this.fullName = fullName;
        this.username = username;
    }

    public Long getCashierId() {
        return cashierId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getUsername() {
        return username;
    }
}

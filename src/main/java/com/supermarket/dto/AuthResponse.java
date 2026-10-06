package com.supermarket.dto;

public class AuthResponse {

    private Long cashierId;
    private String fullName;
    private String username;
    private String role;
    private String token;

    public AuthResponse(Long cashierId, String fullName, String username, String role, String token) {
        this.cashierId = cashierId;
        this.fullName = fullName;
        this.username = username;
        this.role = role;
        this.token = token;
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

    public String getRole() {
        return role;
    }

    public String getToken() {
        return token;
    }
}

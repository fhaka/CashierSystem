package com.supermarket.dto;

public class UserResponse {

    private final Long id;
    private final String fullName;
    private final String username;
    private final String role;
    private final boolean active;

    public UserResponse(Long id, String fullName, String username, String role, boolean active) {
        this.id = id;
        this.fullName = fullName;
        this.username = username;
        this.role = role;
        this.active = active;
    }

    public Long getId() {
        return id;
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

    public boolean isActive() {
        return active;
    }
}

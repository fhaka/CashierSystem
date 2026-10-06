package com.supermarket.dto;

public class UserResponse {

    private final Long id;
    private final String fullName;
    private final String username;
    private final String role;
    private final boolean active;
    private final boolean hasApprovalPin;

    public UserResponse(Long id, String fullName, String username, String role, boolean active, boolean hasApprovalPin) {
        this.id = id;
        this.fullName = fullName;
        this.username = username;
        this.role = role;
        this.active = active;
        this.hasApprovalPin = hasApprovalPin;
    }

    public boolean isHasApprovalPin() {
        return hasApprovalPin;
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

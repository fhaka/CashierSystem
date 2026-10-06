package com.supermarket.dto;

public class UserRequest {

    private String fullName;
    private String username;
    private String password;
    private String role;
    private Boolean active;
    private String approvalPin;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getApprovalPin() {
        return approvalPin;
    }

    public void setApprovalPin(String approvalPin) {
        this.approvalPin = approvalPin;
    }
}

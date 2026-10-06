package com.supermarket.model;

public enum CashierRole {
    SUPER_ADMIN,
    SUPER_CASHIER,
    CASHIER;

    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }

    public boolean isOperationalManager() {
        return isSuperAdmin() || this == SUPER_CASHIER;
    }
}

package com.supermarket.model;

public enum PaymentMethod {
    CASH,
    CARD,
    /** Charged to the customer's account ("në borxh"). */
    CREDIT,
    /** Paid with loyalty points. */
    POINTS
}

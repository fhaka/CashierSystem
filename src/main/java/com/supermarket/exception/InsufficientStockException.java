package com.supermarket.exception;

public class InsufficientStockException extends LocalizedException {

    public InsufficientStockException(String productName) {
        super("stock.insufficient", productName);
    }
}

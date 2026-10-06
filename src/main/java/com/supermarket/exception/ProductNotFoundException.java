package com.supermarket.exception;

public class ProductNotFoundException extends LocalizedException {

    public ProductNotFoundException(Long id) {
        super("product.notFound", id);
    }
}

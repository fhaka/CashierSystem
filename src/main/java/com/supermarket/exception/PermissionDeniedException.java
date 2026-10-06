package com.supermarket.exception;

public class PermissionDeniedException extends LocalizedException {

    public PermissionDeniedException(String code) {
        super(code);
    }
}

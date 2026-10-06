package com.supermarket.exception;

/** The request breaks a business rule (missing field, closed shift, duplicate number...). Answered with 400. */
public class ValidationException extends LocalizedException {

    public ValidationException(String code, Object... args) {
        super(code, args);
    }
}

package com.supermarket.exception;

public class AuthenticationRequiredException extends LocalizedException {

    public AuthenticationRequiredException(String code) {
        super(code);
    }
}

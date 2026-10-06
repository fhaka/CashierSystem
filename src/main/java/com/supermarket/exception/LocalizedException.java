package com.supermarket.exception;

import java.util.Arrays;

/**
 * An error the user should read. It carries a message code from messages*.properties instead of text,
 * so the same error is shown in the language of the till that caused it.
 */
public abstract class LocalizedException extends RuntimeException {

    private final String code;
    private final Object[] args;

    protected LocalizedException(String code, Object... args) {
        this(code, null, args);
    }

    protected LocalizedException(String code, Throwable cause, Object... args) {
        super(code, cause);
        this.code = code;
        // Strings, so ids and amounts are not reformatted with locale-specific digit grouping.
        this.args = Arrays.stream(args).map(String::valueOf).toArray();
    }

    public String getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }
}

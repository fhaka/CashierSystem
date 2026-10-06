package com.supermarket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Every endpoint answers with this shape. Errors carry a translated message for people and,
 * when useful, a stable code for the screen (for example "approval.required" to ask for a manager PIN).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    private final String code;

    public ApiResponse(boolean success, String message, T data) {
        this(success, message, data, null);
    }

    private ApiResponse(boolean success, String message, T data, String code) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.code = code;
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, message, null, code);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getCode() {
        return code;
    }
}

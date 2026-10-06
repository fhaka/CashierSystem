package com.supermarket.exception;

import com.supermarket.dto.ApiResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns errors into {@code ApiResponse} bodies. Messages are resolved in the language the till asked for
 * with the Accept-Language header (Albanian when none is given).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleProductNotFound(ProductNotFoundException exception) {
        return localized(HttpStatus.NOT_FOUND, exception);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientStock(InsufficientStockException exception) {
        return localized(HttpStatus.BAD_REQUEST, exception);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException exception) {
        return localized(HttpStatus.BAD_REQUEST, exception);
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationRequiredException exception) {
        return localized(HttpStatus.UNAUTHORIZED, exception);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePermissionDenied(PermissionDeniedException exception) {
        return localized(HttpStatus.FORBIDDEN, exception);
    }

    @ExceptionHandler(ReceiptPrinterException.class)
    public ResponseEntity<ApiResponse<Void>> handlePrinter(ReceiptPrinterException exception) {
        return localized(HttpStatus.SERVICE_UNAVAILABLE, exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(message("error.invalidRequest")));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(message("error.database")));
    }

    private ResponseEntity<ApiResponse<Void>> localized(HttpStatus status, LocalizedException exception) {
        String text = messageSource.getMessage(
                exception.getCode(), exception.getArgs(), exception.getCode(), LocaleContextHolder.getLocale());
        return ResponseEntity.status(status).body(ApiResponse.error(text));
    }

    private String message(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }
}

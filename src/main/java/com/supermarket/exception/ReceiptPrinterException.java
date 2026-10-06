package com.supermarket.exception;

/** The receipt printer is missing or refused the job. Answered with 503 so the till can retry. */
public class ReceiptPrinterException extends LocalizedException {

    public ReceiptPrinterException(String code, Object... args) {
        super(code, args);
    }

    public ReceiptPrinterException(String code, Throwable cause, Object... args) {
        super(code, cause, args);
    }
}

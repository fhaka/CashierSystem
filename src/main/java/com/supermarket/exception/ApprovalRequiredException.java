package com.supermarket.exception;

/** The action needs a manager's PIN (missing, wrong, or approvals paused). Answered with 403 and the code. */
public class ApprovalRequiredException extends LocalizedException {

    public ApprovalRequiredException(String code, Object... args) {
        super(code, args);
    }
}

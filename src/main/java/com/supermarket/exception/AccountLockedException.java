package com.supermarket.exception;

/** Too many wrong passwords: the account is locked for a few minutes. Answered with 429. */
public class AccountLockedException extends LocalizedException {

    public AccountLockedException(long minutes) {
        super("auth.locked", minutes);
    }
}

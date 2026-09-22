package com.viris.PulseGuard.common.exception;

public class EmailAlreadyUsedException extends RuntimeException {
    public EmailAlreadyUsedException(String email) {
        super("Email already registered: " + email);
    }
}

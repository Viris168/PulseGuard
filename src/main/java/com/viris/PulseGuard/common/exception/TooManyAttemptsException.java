package com.viris.PulseGuard.common.exception;

public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException() {
        super("Too many login attempts. Try again later.");
    }
}

package com.viris.PulseGuard.common.exception;

/** Unknown, used or expired verification or email-change link. */
public class InvalidEmailTokenException extends RuntimeException {
    public InvalidEmailTokenException() {
        super("This link is invalid or has expired.");
    }
}

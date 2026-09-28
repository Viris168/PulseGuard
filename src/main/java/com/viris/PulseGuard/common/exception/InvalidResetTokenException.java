package com.viris.PulseGuard.common.exception;

/** Unknown, used or expired: one message, so a link's history is never revealed. */
public class InvalidResetTokenException extends RuntimeException {
    public InvalidResetTokenException() {
        super("This reset link is invalid or has expired. Request a new one.");
    }
}

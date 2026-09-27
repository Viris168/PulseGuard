package com.viris.PulseGuard.common.exception;

/** A target that does not fit its channel type; reported as a field error on {@code target}. */
public class InvalidChannelTargetException extends RuntimeException {
    public InvalidChannelTargetException(String message) {
        super(message);
    }
}

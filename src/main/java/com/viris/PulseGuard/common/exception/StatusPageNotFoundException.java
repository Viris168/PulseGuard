package com.viris.PulseGuard.common.exception;

/** A slug that does not exist and one that is unpublished look the same from outside. */
public class StatusPageNotFoundException extends RuntimeException {
    public StatusPageNotFoundException() {
        super("Status page not found");
    }
}

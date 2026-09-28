package com.viris.PulseGuard.common.exception;

/** A ping URL whose token matches no monitor: never created, deleted, or mistyped. */
public class HeartbeatNotFoundException extends RuntimeException {
    public HeartbeatNotFoundException() {
        super("Unknown ping URL. Check it against the one shown on the monitor.");
    }
}

package com.viris.PulseGuard.common.exception;

/** Another account's status page already uses this address. */
public class SlugTakenException extends RuntimeException {
    public SlugTakenException() {
        super("That address is taken");
    }
}

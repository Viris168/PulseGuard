package com.viris.PulseGuard.common.exception;

public class PasswordUnchangedException extends RuntimeException {
    public PasswordUnchangedException() {
        super("New password must differ from the current one");
    }
}

package com.viris.PulseGuard.common.exception;

/** Missing and another tenant's key look the same: 404. */
public class ApiKeyNotFoundException extends RuntimeException {
    public ApiKeyNotFoundException(Long id) {
        super("API key not found: " + id);
    }
}

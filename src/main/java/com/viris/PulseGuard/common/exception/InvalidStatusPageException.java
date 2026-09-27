package com.viris.PulseGuard.common.exception;

import java.util.Map;

/** Status page checks that need the normalised input; reported like body validation. */
public class InvalidStatusPageException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidStatusPageException(Map<String, String> fieldErrors) {
        super("Validation failed");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}

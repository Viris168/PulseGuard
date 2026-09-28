package com.viris.PulseGuard.common.exception;

import java.util.Map;

/** A monitor change the request's own validation cannot see, reported against one field. */
public class InvalidMonitorException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidMonitorException(String field, String message) {
        super("Validation failed");
        this.fieldErrors = Map.of(field, message);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}

package com.viris.PulseGuard.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A well-formed create request that is not allowed: a duplicate name, or too many keys. */
public class ApiKeyRuleException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    private ApiKeyRuleException(HttpStatus status, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    public static ApiKeyRuleException duplicateName() {
        return new ApiKeyRuleException(HttpStatus.CONFLICT, "Validation failed",
                Map.of("name", "You already have a key with that name"));
    }

    public static ApiKeyRuleException tooMany(int max) {
        return new ApiKeyRuleException(HttpStatus.BAD_REQUEST,
                "You can have up to " + max + " keys. Revoke one you no longer use.", Map.of());
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}

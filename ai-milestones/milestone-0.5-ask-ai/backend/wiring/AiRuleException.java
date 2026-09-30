package com.viris.PulseGuard.common.exception;

import org.springframework.http.HttpStatus;

/** An Ask AI request that can't be answered right now. The message is safe to show the user. */
public class AiRuleException extends RuntimeException {

    private final HttpStatus status;

    private AiRuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static AiRuleException disabled() {
        return new AiRuleException(HttpStatus.FORBIDDEN, "Turn on Ask AI first.");
    }

    public static AiRuleException noMonitorSelected() {
        return new AiRuleException(HttpStatus.BAD_REQUEST, "Select at least one monitor.");
    }

    public static AiRuleException quotaReached(String message) {
        return new AiRuleException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static AiRuleException notConfigured() {
        return new AiRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Ask AI isn't available on this server.");
    }

    /** The provider failed or was too slow; the question was handed back. */
    public static AiRuleException unavailable() {
        return new AiRuleException(HttpStatus.SERVICE_UNAVAILABLE,
                "Ask AI couldn't answer just now. Try again in a moment; this question wasn't counted.");
    }

    public HttpStatus getStatus() {
        return status;
    }
}

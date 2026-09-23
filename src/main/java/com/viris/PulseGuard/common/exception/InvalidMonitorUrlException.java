package com.viris.PulseGuard.common.exception;

import com.viris.PulseGuard.enumeration.ErrorType;

/**
 * Thrown when a monitor URL is rejected before saving or before a check —
 * unresolvable host, or a host resolving to a private/internal address (SSRF guard).
 * The message must never echo the resolved address.
 */
public class InvalidMonitorUrlException extends RuntimeException {

    private final ErrorType errorType;

    public InvalidMonitorUrlException(String reason) {
        this(reason, ErrorType.CONNECTION);
    }

    public InvalidMonitorUrlException(String reason, ErrorType errorType) {
        super("Monitor URL rejected: " + reason);
        this.errorType = errorType;
    }

    /** How this rejection is recorded when it happens during a check. */
    public ErrorType getErrorType() {
        return errorType;
    }
}

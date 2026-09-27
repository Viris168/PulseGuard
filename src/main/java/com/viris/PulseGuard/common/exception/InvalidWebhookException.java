package com.viris.PulseGuard.common.exception;

/**
 * A webhook request that is not provably from Stripe: missing, forged or expired signature.
 * Answered with a 400 and nothing is processed.
 */
public class InvalidWebhookException extends RuntimeException {

    public InvalidWebhookException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public InvalidWebhookException(String reason) {
        super(reason);
    }
}

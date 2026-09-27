package com.viris.PulseGuard.common.exception;

/**
 * Stripe could not be reached or rejected a request. The message is safe to show; Stripe's own
 * error text stays in the log, since it can name internal ids and account details.
 */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(Throwable cause) {
        super("Billing is temporarily unavailable. Please try again in a moment.", cause);
    }
}

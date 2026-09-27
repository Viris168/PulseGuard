package com.viris.PulseGuard.common.exception;

/**
 * A Stripe subscription could not be applied to an account. Never shown to users: it fails the
 * webhook with a 500 so Stripe retries, which fixes itself once the config or data is corrected.
 */
public class BillingSyncException extends RuntimeException {

    private BillingSyncException(String message) {
        super(message);
    }

    public static BillingSyncException unknownCustomer(String subscriptionId, String customerId) {
        return new BillingSyncException("Subscription " + subscriptionId
                + " belongs to Stripe customer " + customerId + ", which no account is linked to");
    }

    public static BillingSyncException unknownPrice(String subscriptionId, String priceId) {
        return new BillingSyncException("Subscription " + subscriptionId + " uses price " + priceId
                + ", which matches no plan in pulseguard.stripe.prices");
    }
}

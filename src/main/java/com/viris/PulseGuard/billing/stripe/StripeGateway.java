package com.viris.PulseGuard.billing.stripe;

import com.viris.PulseGuard.common.exception.PaymentProviderException;

/**
 * Everything PulseGuard asks of the Stripe API. The only seam to the SDK, so billing logic is
 * tested against a mock and a provider change stays in one class. Webhook signature checks are
 * not here: they are local HMAC math, not API calls.
 *
 * <p>Every method throws {@link PaymentProviderException} when Stripe cannot be reached or
 * rejects the request.
 */
public interface StripeGateway {

    /**
     * Creates the Stripe customer for a user. Idempotent per user for 24 hours (Stripe's key
     * lifetime), so a retry after a failed save returns the same customer instead of a second one.
     *
     * @return the customer id, {@code cus_...}
     */
    String createCustomer(Long userId, String email, String name);

    /** @return the Stripe-hosted Checkout URL to send the browser to. */
    String createCheckoutSession(CheckoutSessionRequest request);

    /** @return the Stripe-hosted Customer Portal URL to send the browser to. */
    String createPortalSession(String customerId, String returnUrl);

    /** Cancels at once (not at period end): used when the account itself is deleted. */
    void cancelSubscription(String subscriptionId);

    /** Keeps receipts and portal emails going to the address the account uses now. */
    void updateCustomerEmail(String customerId, String email);

    /** The subscription as Stripe has it now; webhooks sync from this, not from the event payload. */
    SubscriptionSnapshot retrieveSubscription(String subscriptionId);
}

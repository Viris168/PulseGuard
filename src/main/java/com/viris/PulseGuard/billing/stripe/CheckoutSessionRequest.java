package com.viris.PulseGuard.billing.stripe;

/**
 * A subscription Checkout for one price.
 *
 * @param userId stored on the session and the subscription, so webhooks can be traced back to
 *               the account even if the customer lookup ever fails.
 */
public record CheckoutSessionRequest(
        String customerId,
        String priceId,
        Long userId,
        String successUrl,
        String cancelUrl
) {
}

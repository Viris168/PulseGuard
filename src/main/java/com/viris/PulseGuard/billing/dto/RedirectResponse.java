package com.viris.PulseGuard.billing.dto;

/** A Stripe-hosted page (Checkout or Customer Portal) for the browser to go to. */
public record RedirectResponse(String url) {
}

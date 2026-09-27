package com.viris.PulseGuard.enumeration;

import java.util.Arrays;

/**
 * Stripe's subscription statuses, and which of them keep the paid plan.
 *
 * <p>{@code PAST_DUE} keeps it: Stripe is still retrying the card, and cutting features on the
 * first failed charge would punish an expired card. When retries run out, Stripe moves the
 * subscription to {@code CANCELED} or {@code UNPAID}, and the account drops to FREE then.
 */
public enum SubscriptionStatus {

    INCOMPLETE("incomplete", false),
    INCOMPLETE_EXPIRED("incomplete_expired", false),
    TRIALING("trialing", true),
    ACTIVE("active", true),
    PAST_DUE("past_due", true),
    CANCELED("canceled", false),
    UNPAID("unpaid", false),
    PAUSED("paused", false);

    private final String stripeValue;
    private final boolean grantsPlan;

    SubscriptionStatus(String stripeValue, boolean grantsPlan) {
        this.stripeValue = stripeValue;
        this.grantsPlan = grantsPlan;
    }

    /**
     * @throws IllegalArgumentException for a status this version does not know. The SDK pins the
     *                                  API version, so that only happens after an SDK upgrade.
     */
    public static SubscriptionStatus fromStripe(String value) {
        return Arrays.stream(values())
                .filter(status -> status.stripeValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Stripe subscription status: " + value));
    }

    /** The lowercase form Stripe (and the billing API) uses, e.g. {@code past_due}. */
    public String stripeValue() {
        return stripeValue;
    }

    /** Whether the account keeps the plan it pays for while the subscription is in this status. */
    public boolean grantsPlan() {
        return grantsPlan;
    }
}

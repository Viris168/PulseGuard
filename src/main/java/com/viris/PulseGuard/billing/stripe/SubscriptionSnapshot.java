package com.viris.PulseGuard.billing.stripe;

import java.time.Instant;

/**
 * The parts of a Stripe subscription PulseGuard stores, flattened out of the SDK model.
 *
 * @param status           Stripe's status string: active, trialing, past_due, canceled, unpaid,
 *                         incomplete, incomplete_expired or paused.
 * @param priceId          price of the subscription's item; null only if Stripe returns no item.
 * @param currentPeriodEnd end of the paid period. Read from the item: since API 2025-03-31 it
 *                         is no longer on the subscription itself. For a subscription that is
 *                         ending, the date it ends, if that is earlier ({@code cancel_at}).
 * @param cancelAtPeriodEnd whether the subscription is scheduled to end (stays paid until then),
 *                          set by either {@code cancel_at_period_end} or {@code cancel_at}.
 */
public record SubscriptionSnapshot(
        String id,
        String customerId,
        String status,
        String priceId,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd
) {
}

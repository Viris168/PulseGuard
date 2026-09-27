package com.viris.PulseGuard.billing.dto;

import com.viris.PulseGuard.enumeration.Plan;

import java.time.Instant;

/**
 * GET /api/billing/subscription. The subscription fields describe a live subscription only;
 * on Free (never subscribed, or ended) they are null/false.
 *
 * @param status Stripe's lowercase status, e.g. {@code active} or {@code past_due}.
 */
public record BillingSummaryResponse(
        Plan plan,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Usage usage
) {
    public record Usage(long monitors) {
    }
}

package com.viris.PulseGuard.billing.dto;

import com.viris.PulseGuard.enumeration.Plan;
import jakarta.validation.constraints.NotNull;

/** POST /api/billing/checkout. Only paid plans can be bought; FREE is rejected by the service. */
public record CheckoutRequest(@NotNull Plan plan) {
}

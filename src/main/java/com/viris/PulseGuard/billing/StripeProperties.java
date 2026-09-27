package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.enumeration.Plan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/**
 * Stripe account settings. Every value comes from the environment; none is ever logged
 * (see {@link #toString()}).
 *
 * @param secretKey         API key, {@code sk_test_...} locally.
 * @param webhookSecret     signing secret of the webhook endpoint ({@code whsec_...}); the
 *                          Stripe CLI prints one for local forwarding.
 * @param prices            Stripe price id per paid plan. Exactly PRO and BUSINESS: FREE has no price.
 * @param appBaseUrl        where the frontend runs; Checkout and the portal send users back here.
 * @param connectTimeout    handshake limit for calls to the Stripe API.
 * @param readTimeout       response limit for calls to the Stripe API.
 * @param maxNetworkRetries SDK retries on network errors and 409/5xx; safe because the SDK
 *                          sends an idempotency key with every retried POST.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.stripe")
public record StripeProperties(
        @NotBlank String secretKey,
        @NotBlank String webhookSecret,
        @NotNull Map<Plan, String> prices,
        @NotNull URI appBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @PositiveOrZero int maxNetworkRetries
) {

    private static final EnumSet<Plan> PAID_PLANS = EnumSet.of(Plan.PRO, Plan.BUSINESS);

    public StripeProperties {
        // Binding fails here, at startup, instead of on a customer's first upgrade.
        if (prices != null) {
            if (!prices.keySet().equals(PAID_PLANS)) {
                throw new IllegalArgumentException(
                        "pulseguard.stripe.prices must set exactly " + PAID_PLANS + ", got " + prices.keySet());
            }
            if (prices.values().stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("pulseguard.stripe.prices must not contain blank price ids");
            }
            if (prices.values().stream().distinct().count() != prices.size()) {
                throw new IllegalArgumentException("pulseguard.stripe.prices must use a different price per plan");
            }
            prices = Map.copyOf(prices);
        }
        if (appBaseUrl != null && !("http".equals(appBaseUrl.getScheme()) || "https".equals(appBaseUrl.getScheme()))) {
            throw new IllegalArgumentException("pulseguard.stripe.app-base-url must be an http(s) URL");
        }
    }

    /** The Stripe price a paid plan is sold at. */
    public String priceFor(Plan plan) {
        String priceId = prices.get(plan);
        if (priceId == null) {
            throw new IllegalArgumentException("No Stripe price for plan " + plan);
        }
        return priceId;
    }

    /** Reverse lookup for webhooks; empty for a price this app does not sell. */
    public Optional<Plan> planForPrice(String priceId) {
        return prices.entrySet().stream()
                .filter(entry -> entry.getValue().equals(priceId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** An absolute frontend URL, e.g. {@code appUrl("/billing?checkout=cancelled")}. */
    public String appUrl(String pathAndQuery) {
        String base = appBaseUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + pathAndQuery;
    }

    /** Records print every component by default; the two secrets must never reach a log. */
    @Override
    public String toString() {
        return "StripeProperties[secretKey=***, webhookSecret=***, prices=" + prices
                + ", appBaseUrl=" + appBaseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", maxNetworkRetries=" + maxNetworkRetries + "]";
    }
}

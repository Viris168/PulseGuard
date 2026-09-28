package com.viris.PulseGuard.common.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * Where the frontend runs, for links the backend hands out (alerts' "Open in PulseGuard").
 * Reads the same PULSEGUARD_APP_BASE_URL as the Stripe redirects.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.app")
public record AppProperties(@NotNull URI baseUrl) {

    public AppProperties {
        if (baseUrl != null && !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("pulseguard.app.base-url must be an http(s) URL");
        }
    }

    /** An absolute frontend URL, e.g. {@code url("/incidents/42")}. */
    public String url(String path) {
        String base = baseUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }
}

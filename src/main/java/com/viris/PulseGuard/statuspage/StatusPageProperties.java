package com.viris.PulseGuard.statuspage;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** @param cacheTtl how long a rendered public page is served from Redis before recomputing. */
@Validated
@ConfigurationProperties(prefix = "pulseguard.status-page")
public record StatusPageProperties(@NotNull Duration cacheTtl) {
}

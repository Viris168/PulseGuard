package com.viris.PulseGuard.stats;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** @param cacheTtl how long a computed stats payload is served from Redis before recomputing. */
@Validated
@ConfigurationProperties(prefix = "pulseguard.stats")
public record StatsProperties(@NotNull Duration cacheTtl) {
}

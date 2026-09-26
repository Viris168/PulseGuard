package com.viris.PulseGuard.incident.dto;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated at startup, so a bad value stops the app from booting instead of quietly
 * causing false alarms in production.
 *
 * @param failureThreshold  consecutive failed checks that turn a monitor DOWN and open an
 *                          incident. At least 2: a single failed check must never open one.
 * @param recoveryThreshold consecutive passing checks that turn a DOWN monitor UP again and
 *                          resolve its incident. At least 1, so resolving needs a real pass.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.incident")
public record IncidentProperties(
        @Min(2) int failureThreshold,
        @Min(1) int recoveryThreshold
) {
}

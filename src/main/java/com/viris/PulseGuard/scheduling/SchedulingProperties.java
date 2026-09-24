package com.viris.PulseGuard.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param jitter             upper bound on the random delay before a monitor's first check
 *                           (further capped by its interval), so monitors created together —
 *                           or rescheduled together at startup — don't all fire in one burst.
 * @param reconcileOnStartup when true, startup re-syncs Quartz jobs with the active monitors
 *                           in the database, which stays the source of truth.
 */
@ConfigurationProperties(prefix = "pulseguard.scheduling")
public record SchedulingProperties(
        Duration jitter,
        boolean reconcileOnStartup
) {
}

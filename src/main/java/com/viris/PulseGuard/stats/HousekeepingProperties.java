package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.enumeration.StatsRange;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.quartz.CronExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;

/**
 * The nightly rollup and retention job.
 *
 * @param enabled      false removes the job; nothing is summarised or deleted.
 * @param cron         Quartz cron in UTC; default 03:15 every night.
 * @param rawCheckDays raw checks and pings are kept this long at most (less if the plan keeps less
 *                     history). The dashboard reads raw checks for its longest range plus the
 *                     previous one it compares with, so a smaller value is refused at startup.
 * @param batchSize    rows per delete; each batch is its own short transaction.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.housekeeping")
public record HousekeepingProperties(boolean enabled, @NotBlank String cron, int rawCheckDays,
                                     @Min(1) int batchSize) {

    /** The longest dashboard range and the equally long one before it, in days. */
    static final long MIN_RAW_CHECK_DAYS = 2 * Arrays.stream(StatsRange.values())
            .mapToLong(StatsRange::days).max().orElseThrow();

    public HousekeepingProperties {
        // Checked here so a typo fails as a clear config error, not later inside Quartz.
        if (cron != null && !cron.isBlank() && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("pulseguard.housekeeping.cron is not a valid Quartz cron: '" + cron + "'");
        }
        if (rawCheckDays < MIN_RAW_CHECK_DAYS) {
            throw new IllegalArgumentException("pulseguard.housekeeping.raw-check-days must be at least "
                    + MIN_RAW_CHECK_DAYS + ": the dashboard reads that much raw history");
        }
    }
}

package com.viris.PulseGuard.stats.dto;

import java.time.Instant;

/** One bar of the uptime strip. {@code downtimeSeconds} comes from incidents, not from checks. */
public record UptimeBucket(Instant start, Instant end, Double uptimePct, long downtimeSeconds) {
}

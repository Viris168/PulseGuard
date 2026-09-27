package com.viris.PulseGuard.stats.dto;

import java.time.Instant;

/** One point of the response-time chart; nulls mean no data (or no successful check) there. */
public record SeriesPoint(Instant start, Instant end, Integer avgResponseMs, Double uptimePct) {
}

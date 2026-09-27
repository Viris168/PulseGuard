package com.viris.PulseGuard.stats.dto;

/** The card figures for one period; also the "previous period" comparison. */
public record PeriodSummary(Double uptimePct, Integer avgResponseMs, Integer p95ResponseMs,
                            long checksCount, int incidentCount, long downtimeSeconds) {
}

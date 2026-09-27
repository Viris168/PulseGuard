package com.viris.PulseGuard.stats.dto;

import com.viris.PulseGuard.enumeration.StatsRange;

import java.util.List;

/**
 * GET /api/monitors/{id}/stats; mirrors {@code MonitorRangeStats} in
 * frontend/src/types/check.ts. {@code previous} is null when the plan's history does not
 * reach back a second period, or when that period had no checks.
 */
public record MonitorRangeStats(
        StatsRange range,
        Double uptimePct,
        Integer avgResponseMs,
        Integer p95ResponseMs,
        long checksCount,
        int incidentCount,
        long downtimeSeconds,
        List<SeriesPoint> responseSeries,
        List<UptimeBucket> uptimeBuckets,
        PeriodSummary previous
) {
}

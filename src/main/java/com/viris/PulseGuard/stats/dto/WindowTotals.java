package com.viris.PulseGuard.stats.dto;

/**
 * Check totals for a time window or one bucket of it, as computed by Postgres. Response times
 * cover passing checks only: a fast 500 would otherwise make an outage look like a speed-up.
 *
 * @param index bucket number from the window start; -1 for a whole-window total
 */
public record WindowTotals(int index, long total, long up, Double avgMs, Double p95Ms) {

    public static WindowTotals empty() {
        return new WindowTotals(-1, 0, 0, null, null);
    }
}

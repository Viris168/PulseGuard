package com.viris.PulseGuard.enumeration;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Duration;

/**
 * Dashboard time ranges; the bucket sizes match frontend/src/api/mockHistory.ts. Uptime bars
 * are always a whole number of chart buckets, so they are built by merging chart buckets.
 * Written and read as "24h" / "7d" / "30d", not as the constant name.
 */
public enum StatsRange {
    H24("24h", Duration.ofHours(24), Duration.ofMinutes(15), 4),   // 96 points, 24 hourly bars
    D7("7d", Duration.ofDays(7), Duration.ofHours(1), 6),          // 168 points, 28 six-hour bars
    D30("30d", Duration.ofDays(30), Duration.ofHours(4), 6);       // 180 points, 30 daily bars

    private final String label;
    private final Duration span;
    private final Duration seriesBucket;
    private final int seriesBucketsPerUptimeBucket;

    StatsRange(String label, Duration span, Duration seriesBucket, int seriesBucketsPerUptimeBucket) {
        this.label = label;
        this.span = span;
        this.seriesBucket = seriesBucket;
        this.seriesBucketsPerUptimeBucket = seriesBucketsPerUptimeBucket;
    }

    public static StatsRange fromLabel(String label) {
        for (StatsRange range : values()) {
            if (range.label.equals(label)) {
                return range;
            }
        }
        throw new IllegalArgumentException("Unknown range: " + label);
    }

    @JsonValue
    public String label() {
        return label;
    }

    public Duration span() {
        return span;
    }

    public Duration seriesBucket() {
        return seriesBucket;
    }

    public int seriesBucketsPerUptimeBucket() {
        return seriesBucketsPerUptimeBucket;
    }

    /** Whole days of history the range needs; 24h counts as one. */
    public long days() {
        return Math.max(1, span.toDays());
    }

    /** The label, so validation errors list "24h, 7d, 30d" rather than constant names. */
    @Override
    public String toString() {
        return label;
    }
}

package com.viris.PulseGuard.stats.dto;

import java.time.Instant;

/** An incident as a time interval; an open incident ends "now". */
public record Downtime(Instant start, Instant end) {
}

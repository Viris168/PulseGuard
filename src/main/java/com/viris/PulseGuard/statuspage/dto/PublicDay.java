package com.viris.PulseGuard.statuspage.dto;

import java.time.Instant;

/** One UTC day. {@code uptimePct} is null when nothing was checked that day. */
public record PublicDay(Instant date, Double uptimePct, long downtimeSeconds) {
}

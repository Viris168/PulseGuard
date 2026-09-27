package com.viris.PulseGuard.statuspage.dto;

import com.viris.PulseGuard.enumeration.IncidentStatus;

import java.time.Instant;

/** @param recovering open, but checks are passing again (monitor RECOVERING) */
public record PublicIncident(
        Long id,
        String componentName,
        IncidentStatus status,
        boolean recovering,
        Instant startedAt,
        Instant resolvedAt
) {
}

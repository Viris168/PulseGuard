package com.viris.PulseGuard.incident.dto;

import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.Incident;

import java.time.Instant;

/**
 * One incident for lists; mirrors {@code Incident} in frontend/src/types/incident.ts.
 * {@link #from} reads the monitor's name, so the monitor must already be loaded (join fetch).
 */
public record IncidentResponse(
        Long id,
        Long monitorId,
        String monitorName,
        IncidentStatus status,
        String cause,
        Instant startedAt,
        Instant resolvedAt
) {
    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(incident.getId(), incident.getMonitor().getId(),
                incident.getMonitor().getName(), incident.getStatus(), incident.getCause(),
                incident.getStartedAt(), incident.getResolvedAt());
    }
}

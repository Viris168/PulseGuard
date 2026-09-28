package com.viris.PulseGuard.statuspage.dto;

import com.viris.PulseGuard.enumeration.IncidentStatus;

import java.time.Instant;

/**
 * No id: incident ids are sequential across all accounts, so publishing them would tell
 * visitors roughly how many incidents PulseGuard has seen overall.
 *
 * @param recovering open, but checks are passing again (monitor RECOVERING)
 */
public record PublicIncident(
        String componentName,
        IncidentStatus status,
        boolean recovering,
        Instant startedAt,
        Instant resolvedAt
) {
}

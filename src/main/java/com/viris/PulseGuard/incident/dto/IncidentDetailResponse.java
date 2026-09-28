package com.viris.PulseGuard.incident.dto;

import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.monitor.Monitor;

import java.time.Instant;
import java.util.List;

/** One incident with its story; mirrors {@code IncidentDetail} in frontend/src/types/incident.ts. */
public record IncidentDetailResponse(
        Long id,
        Long monitorId,
        String monitorName,
        IncidentStatus status,
        String cause,
        Instant startedAt,
        Instant resolvedAt,
        String monitorType,
        String monitorUrl,
        String monitorMethod,
        int intervalSeconds,
        List<TimelineEvent> timeline
) {
    public static IncidentDetailResponse from(Incident incident, List<TimelineEvent> timeline) {
        Monitor monitor = incident.getMonitor();
        return new IncidentDetailResponse(incident.getId(), monitor.getId(), monitor.getName(),
                incident.getStatus(), incident.getCause(), incident.getStartedAt(), incident.getResolvedAt(),
                monitor.getType().name(), monitor.getUrl(), monitor.getMethod(), monitor.getIntervalSeconds(), timeline);
    }
}

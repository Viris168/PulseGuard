package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.monitor.Monitor;

import java.time.Instant;

public record MonitorResponse(
        Long id,
        String name,
        String url,
        String method,
        Integer expectedStatus,
        Integer intervalSeconds,
        Integer timeoutMs,
        MonitorState state,
        Boolean isActive,
        Instant lastCheckedAt,
        Instant createdAt
) {
    public static MonitorResponse from(Monitor monitor) {
        return new MonitorResponse(
                monitor.getId(),
                monitor.getName(),
                monitor.getUrl(),
                monitor.getMethod(),
                monitor.getExpectedStatus(),
                monitor.getIntervalSeconds(),
                monitor.getTimeoutMs(),
                monitor.getState(),
                monitor.isActive(),
                monitor.getLastCheckedAt(),
                monitor.getCreatedAt()
        );
    }
}
package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.MonitorType;
import com.viris.PulseGuard.monitor.Monitor;

import java.time.Instant;
import java.util.List;

public record MonitorResponse(
        Long id,
        String name,
        String url,
        String method,
        Integer expectedStatus,
        List<Integer> expectedStatuses,
        Integer intervalSeconds,
        Integer timeoutMs,
        MonitorState state,
        Boolean isActive,
        Instant lastCheckedAt,
        Instant createdAt,
        MonitorType type,
        Integer graceSeconds,
        String pingUrl,
        List<HeaderView> headers,
        String requestBody
) {
    /** @param pingUrl the heartbeat's secret URL (PingUrls), null for HTTP monitors */
    public static MonitorResponse from(Monitor monitor, String pingUrl) {
        return new MonitorResponse(
                monitor.getId(),
                monitor.getName(),
                monitor.getUrl(),
                monitor.getMethod(),
                monitor.getExpectedStatuses().getFirst(),
                List.copyOf(monitor.getExpectedStatuses()),
                monitor.getIntervalSeconds(),
                monitor.getTimeoutMs(),
                monitor.getState(),
                monitor.isActive(),
                monitor.getLastCheckedAt(),
                monitor.getCreatedAt(),
                monitor.getType(),
                monitor.getGraceSeconds(),
                pingUrl,
                monitor.getHeaders().stream().map(HeaderView::from).toList(),
                monitor.getRequestBody()
        );
    }
}
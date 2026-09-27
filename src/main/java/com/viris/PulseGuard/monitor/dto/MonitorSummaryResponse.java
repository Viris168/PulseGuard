package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.monitor.Monitor;

import java.time.Instant;
import java.util.List;

/**
 * A monitor plus its dashboard figures; mirrors {@code MonitorWithStats} in
 * frontend/src/types/monitor.ts, which is flat (Monitor & MonitorStats), hence one record.
 *
 * @param uptime24h    0–100; null when paused or when there were no checks in the window
 * @param recentChecks latest checks, oldest first, true = passed (the mini status bar)
 */
public record MonitorSummaryResponse(
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
        Instant createdAt,
        Double uptime24h,
        Integer lastResponseTimeMs,
        Integer lastStatusCode,
        List<Boolean> recentChecks
) {
    public static MonitorSummaryResponse from(Monitor monitor, Double uptime24h, Integer lastResponseTimeMs,
                                              Integer lastStatusCode, List<Boolean> recentChecks) {
        return new MonitorSummaryResponse(monitor.getId(), monitor.getName(), monitor.getUrl(),
                monitor.getMethod(), monitor.getExpectedStatus(), monitor.getIntervalSeconds(),
                monitor.getTimeoutMs(), monitor.getState(), monitor.isActive(), monitor.getLastCheckedAt(),
                monitor.getCreatedAt(), uptime24h, lastResponseTimeMs, lastStatusCode, recentChecks);
    }
}

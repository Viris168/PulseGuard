package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.MonitorType;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * What Ask AI knows when it answers: the monitors the user shared, their recent figures, and
 * recent incidents. Plain values, no entities, so it is safe to use after the transaction that
 * loaded it and while the model call runs.
 *
 * @param omittedMonitors shared monitors left out to keep the prompt bounded
 * @param hiddenMonitors  the user's monitors not shared with Ask AI
 */
record AskAiSnapshot(Instant now,
                     ZoneId zone,
                     List<MonitorFacts> monitors,
                     int omittedMonitors,
                     int hiddenMonitors,
                     List<IncidentFacts> incidents) {

    /**
     * @param lastFailure the newest failed check's error, only for monitors that aren't UP
     */
    record MonitorFacts(Long id,
                        String name,
                        MonitorType type,
                        MonitorState state,
                        boolean active,
                        int intervalSeconds,
                        Instant lastCheckedAt,
                        Double uptime24h,
                        Double uptime7d,
                        Integer avgMs7d,
                        Integer p95Ms7d,
                        String lastFailure) {
    }

    record IncidentFacts(String monitorName,
                         boolean open,
                         Instant startedAt,
                         Instant resolvedAt,
                         String cause) {
    }
}

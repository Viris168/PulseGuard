package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorType;
import jakarta.validation.constraints.*;

/**
 * Create or update a monitor. Rules shared by both types sit on the fields; the rest depend on
 * the type and are checked by {@link ValidMonitorRequest}, which reports them under the same
 * field names, so every problem comes back in one response.
 *
 * @param type         null means HTTP, for clients written before heartbeats existed
 * @param graceSeconds heartbeat only
 */
@ValidMonitorRequest
public record MonitorRequest(

        MonitorType type,

        @NotBlank(message = "Name is required")
        @Size(max = 100)
        String name,

        String url,

        String method,

        Integer expectedStatus,

        @NotNull(message = "Interval is required")
        @Min(value = 60, message = "Interval must be at least 60 seconds")
        Integer intervalSeconds,

        Integer timeoutMs,

        Integer graceSeconds
) {
    public MonitorType typeOrDefault() {
        return type == null ? MonitorType.HTTP : type;
    }
}

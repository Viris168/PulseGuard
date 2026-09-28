package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorType;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Create or update a monitor. Rules shared by both types sit on the fields; the rest depend on
 * the type and are checked by {@link ValidMonitorRequest}, which reports them under the same
 * field names, so every problem comes back in one response.
 *
 * @param type             null means HTTP, for clients written before heartbeats existed
 * @param expectedStatus   single-code form kept for older clients; used when expectedStatuses is empty
 * @param expectedStatuses HTTP: any of these counts as up
 * @param graceSeconds     heartbeat only
 * @param headers          HTTP only; null or empty for none
 * @param requestBody      HTTP POST and PUT only; blank for none
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

        Integer graceSeconds,

        List<Integer> expectedStatuses,

        List<HeaderInput> headers,

        String requestBody
) {
    public MonitorType typeOrDefault() {
        return type == null ? MonitorType.HTTP : type;
    }

    /** The accepted codes, from the list or the single-code field. Empty if neither is set. */
    public List<Integer> statusesOrDefault() {
        if (expectedStatuses != null && !expectedStatuses.isEmpty()) {
            return expectedStatuses;
        }
        return expectedStatus == null ? List.of() : List.of(expectedStatus);
    }

    public List<HeaderInput> headersOrEmpty() {
        return headers == null ? List.of() : headers;
    }
}

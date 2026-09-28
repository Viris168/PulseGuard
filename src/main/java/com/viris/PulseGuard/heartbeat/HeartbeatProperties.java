package com.viris.PulseGuard.heartbeat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param pingBaseUrl   public origin jobs reach the API at; ping URLs are {@code {base}/api/ping/{token}}
 * @param sweepInterval how often overdue heartbeats are looked for; also the worst-case delay
 *                      between a deadline passing and the monitor going down
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.heartbeat")
public record HeartbeatProperties(@NotBlank String pingBaseUrl, @NotNull Duration sweepInterval) {
}

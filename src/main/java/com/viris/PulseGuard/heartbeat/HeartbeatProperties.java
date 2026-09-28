package com.viris.PulseGuard.heartbeat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param pingBaseUrl   public origin jobs reach the API at; ping URLs are {@code {base}/api/ping/{token}}.
 *                      Must be an http(s) URL, so an unset {@code ${PULSEGUARD_PING_BASE_URL}} (left
 *                      as literal text) stops startup instead of handing out broken ping URLs
 * @param sweepInterval how often overdue heartbeats are looked for; also the worst-case delay
 *                      between a deadline passing and the monitor going down
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.heartbeat")
public record HeartbeatProperties(@NotBlank @Pattern(regexp = "https?://\\S+") String pingBaseUrl,
                                  @NotNull Duration sweepInterval) {
}

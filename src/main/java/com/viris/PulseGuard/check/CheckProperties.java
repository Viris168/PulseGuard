package com.viris.PulseGuard.check;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param connectTimeout how long to wait for the TCP/TLS handshake. Separate from the
 *                       monitor's own {@code timeoutMs}, which bounds the response.
 * @param graceTimeout   slack added to the monitor's timeout for the hard ceiling, so a
 *                       check thread can never hang even if the transport misbehaves.
 * @param userAgent      sent on every check so target operators can identify the traffic.
 * @param maxErrorLength error text longer than this is truncated before it is stored.
 */
@ConfigurationProperties(prefix = "pulseguard.check")
public record CheckProperties(
        Duration connectTimeout,
        Duration graceTimeout,
        String userAgent,
        int maxErrorLength
) {
}

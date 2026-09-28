package com.viris.PulseGuard.notification;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param backoff       wait before each retry, in order; its length is the number of retries.
 *                      Empty turns retries off.
 * @param sweepInterval how often due retries are looked for
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.notification.retry")
public record AlertRetryProperties(@NotNull List<Duration> backoff, @NotNull Duration sweepInterval) {

    /** The wait after the {@code attempts}-th failed send, or null when no retry is left. */
    public Duration delayAfter(int attempts) {
        return attempts >= 1 && attempts <= backoff.size() ? backoff.get(attempts - 1) : null;
    }
}

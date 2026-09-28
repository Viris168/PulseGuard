package com.viris.PulseGuard.notification.channels;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Limits for posting to Slack. Alerts run on a small bounded pool, so a slow Slack must give
 * up quickly rather than hold a thread that other alerts are waiting for.
 *
 * @param connectTimeout handshake limit for hooks.slack.com.
 * @param readTimeout    response limit once connected.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.notification.slack")
public record SlackProperties(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
}

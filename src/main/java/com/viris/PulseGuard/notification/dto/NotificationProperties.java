package com.viris.PulseGuard.notification.dto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param from sender shown on alert emails, e.g. {@code PulseGuard <alerts@example.com>}. */
@Validated
@ConfigurationProperties(prefix = "pulseguard.notification")
public record NotificationProperties(@NotBlank String from) {
}

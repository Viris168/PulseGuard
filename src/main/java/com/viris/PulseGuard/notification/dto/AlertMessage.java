package com.viris.PulseGuard.notification.dto;

/** Channel-neutral alert content; each sender decides how to present it. */
public record AlertMessage(String subject, String body) {
}

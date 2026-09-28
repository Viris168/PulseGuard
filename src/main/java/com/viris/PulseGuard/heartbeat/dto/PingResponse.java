package com.viris.PulseGuard.heartbeat.dto;

import com.viris.PulseGuard.heartbeat.Ping;

import java.time.Instant;

/** Mirrors {@code Ping} in frontend/src/types/check.ts. */
public record PingResponse(Long id, Instant receivedAt, String sourceIp) {

    public static PingResponse from(Ping ping) {
        return new PingResponse(ping.getId(), ping.getReceivedAt(), ping.getSourceIp());
    }
}

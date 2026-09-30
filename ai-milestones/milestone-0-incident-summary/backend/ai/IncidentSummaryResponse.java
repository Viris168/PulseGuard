package com.viris.PulseGuard.ai.dto;

import java.time.Instant;

/** An AI-written incident summary; mirrors {@code IncidentSummary} in frontend/src/api/incidents.ts. */
public record IncidentSummaryResponse(String summary, Instant generatedAt) {
}

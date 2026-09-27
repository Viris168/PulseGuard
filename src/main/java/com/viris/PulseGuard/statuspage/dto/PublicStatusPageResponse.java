package com.viris.PulseGuard.statuspage.dto;

import com.viris.PulseGuard.enumeration.OverallStatus;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/status/{slug}, served to anyone; mirrors {@code PublicStatusPage} in
 * frontend/src/types/statusPage.ts. Deliberately thin: no monitor ids, URLs, response codes
 * or incident causes, only what the owner chose to publish and the status derived from it.
 */
public record PublicStatusPageResponse(
        String title,
        String description,
        OverallStatus overall,
        int historyDays,
        List<PublicComponent> components,
        List<PublicIncident> incidents,
        Instant generatedAt
) {
}

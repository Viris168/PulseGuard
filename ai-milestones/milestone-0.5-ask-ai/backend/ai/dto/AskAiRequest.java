package com.viris.PulseGuard.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/ai/ask. {@code timeZone} is the browser's IANA zone, so answers name times the
 * way the dashboard shows them; unknown or missing means UTC.
 */
public record AskAiRequest(
        @NotBlank(message = "Ask a question") @Size(max = 500, message = "Keep questions under 500 characters")
        String question,
        @Size(max = 64) String timeZone
) {
}

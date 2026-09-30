package com.viris.PulseGuard.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/ai/conversations/{id}/messages. {@code timeZone}: the browser's IANA zone; unknown means UTC. */
public record SendMessageRequest(
        @NotBlank(message = "Ask a question") @Size(max = 500, message = "Keep questions under 500 characters")
        String question,
        @Size(max = 64) String timeZone
) {
}

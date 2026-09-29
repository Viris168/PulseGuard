package com.viris.PulseGuard.ai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** GET/PUT /api/ai/access; mirrors {@code AiAccess} in frontend/src/api/ai.ts. */
public record AiAccessDto(
        boolean enabled,
        boolean allMonitors,
        @NotNull @Size(max = 1000) List<Long> monitorIds
) {
}

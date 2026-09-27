package com.viris.PulseGuard.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same rules as the name in RegisterRequest; stored trimmed. */
public record UpdateProfileRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be 100 characters or fewer")
        String name
) {
}

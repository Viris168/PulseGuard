package com.viris.PulseGuard.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword
) {
}

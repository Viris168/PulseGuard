package com.viris.PulseGuard.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeEmailRequest(
        @NotBlank(message = "New email is required")
        @Email(message = "Email must be valid")
        @Size(max = 255)
        String newEmail,

        @NotBlank(message = "Current password is required")
        String currentPassword
) {
}

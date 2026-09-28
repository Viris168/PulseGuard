package com.viris.PulseGuard.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same password rules as sign-up and password change. */
public record ResetPasswordRequest(
        @NotBlank(message = "The reset link is incomplete")
        @Size(max = 100, message = "The reset link is incomplete")
        String token,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String newPassword
) {
}

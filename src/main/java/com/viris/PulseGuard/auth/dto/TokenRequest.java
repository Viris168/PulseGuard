package com.viris.PulseGuard.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The token from an emailed verification or email-change link. */
public record TokenRequest(
        @NotBlank(message = "The link is incomplete")
        @Size(max = 100, message = "The link is incomplete")
        String token
) {
}

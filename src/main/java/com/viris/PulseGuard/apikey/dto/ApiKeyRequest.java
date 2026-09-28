package com.viris.PulseGuard.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApiKeyRequest(
        @NotBlank(message = "Give the key a name so you know what uses it")
        @Size(max = 50, message = "Keep it under 50 characters")
        String name
) {
}

package com.viris.PulseGuard.notification.dto;

import jakarta.validation.constraints.NotNull;

public record ChannelUpdateRequest(

        @NotNull(message = "Enabled is required")
        Boolean enabled
) {
}

package com.viris.PulseGuard.notification.dto;

import com.viris.PulseGuard.enumeration.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The per-type format of {@code target} is checked in ChannelService, after the plan check. */
public record ChannelRequest(

        @NotNull(message = "Type is required")
        ChannelType type,

        @NotBlank(message = "Target is required")
        @Size(max = 2048)
        String target
) {
}

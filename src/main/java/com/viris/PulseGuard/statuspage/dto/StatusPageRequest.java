package com.viris.PulseGuard.statuspage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/status-page. The slug's format and the monitor list are checked in
 * StatusPageService, after normalising, and reported under {@code slug} and {@code monitors}.
 */
public record StatusPageRequest(

        @NotNull(message = "Address is required")
        String slug,

        @NotBlank(message = "Title is required")
        @Size(max = 80, message = "Keep it under 80 characters")
        String title,

        @Size(max = 280, message = "Keep it under 280 characters")
        String description,

        boolean published,

        @NotNull(message = "Monitors are required")
        @Size(max = 100, message = "A page can show at most 100 monitors")
        List<StatusPageMonitorDto> monitors
) {
}

package com.viris.PulseGuard.statuspage.dto;

import com.viris.PulseGuard.statuspage.StatusPage;

import java.util.List;

/** GET/PUT /api/status-page; mirrors {@code StatusPageConfig} in frontend/src/types/statusPage.ts. */
public record StatusPageResponse(
        String slug,
        String title,
        String description,
        boolean published,
        List<StatusPageMonitorDto> monitors
) {
    public static StatusPageResponse from(StatusPage page) {
        return new StatusPageResponse(
                page.getSlug(),
                page.getTitle(),
                page.getDescription(),
                page.isPublished(),
                page.getEntries().stream()
                        .map(e -> new StatusPageMonitorDto(e.getMonitorId(), e.getDisplayName()))
                        .toList()
        );
    }
}

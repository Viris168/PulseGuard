package com.viris.PulseGuard.ai.help.dto;

/** One entry of GET /api/help; mirrors {@code HelpArticleSummary} in frontend/src/api/help.ts. */
public record HelpArticleSummary(String slug, String title, String summary) {
}

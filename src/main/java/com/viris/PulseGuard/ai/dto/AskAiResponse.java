package com.viris.PulseGuard.ai.dto;

import java.util.List;

/** The answer, links to the pages it talks about, and the quota after this question. */
public record AskAiResponse(String answer, List<AiLink> links, AiQuotaResponse quota) {

    /** A page in the dashboard; {@code to} is a frontend route, never a URL from the model. */
    public record AiLink(String label, String to) {
    }
}

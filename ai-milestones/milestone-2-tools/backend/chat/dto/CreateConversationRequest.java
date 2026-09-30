package com.viris.PulseGuard.ai.chat.dto;

/**
 * POST /api/ai/conversations. Both empty: a plain chat. {@code monitorId}: started from that
 * monitor's page. {@code incidentId}: started from that incident's page. Not both.
 */
public record CreateConversationRequest(Long monitorId, Long incidentId) {

    public static final CreateConversationRequest NONE = new CreateConversationRequest(null, null);
}

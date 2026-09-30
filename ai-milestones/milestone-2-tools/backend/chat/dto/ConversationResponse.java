package com.viris.PulseGuard.ai.chat.dto;

import com.viris.PulseGuard.ai.chat.AiConversation;

import java.time.Instant;

/**
 * One row of the conversation list; mirrors {@code AiConversation} in frontend/src/api/ai.ts.
 * {@code contextMonitorId} / {@code contextIncidentId}: the page the chat was started from, if any.
 */
public record ConversationResponse(Long id, String title, Instant createdAt, Instant updatedAt,
                                   Long contextMonitorId, Long contextIncidentId) {

    public static ConversationResponse from(AiConversation c) {
        return new ConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getContextMonitorId(), c.getContextIncidentId());
    }
}

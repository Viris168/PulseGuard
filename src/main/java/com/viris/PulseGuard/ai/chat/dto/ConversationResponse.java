package com.viris.PulseGuard.ai.chat.dto;

import com.viris.PulseGuard.ai.chat.AiConversation;

import java.time.Instant;

/** One row of the conversation list; mirrors {@code AiConversation} in frontend/src/api/ai.ts. */
public record ConversationResponse(Long id, String title, Instant createdAt, Instant updatedAt) {

    public static ConversationResponse from(AiConversation c) {
        return new ConversationResponse(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
    }
}

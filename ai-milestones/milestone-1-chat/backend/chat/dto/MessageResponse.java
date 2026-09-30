package com.viris.PulseGuard.ai.chat.dto;

import com.viris.PulseGuard.ai.chat.AiMessage;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;

import java.time.Instant;

/**
 * One message as the panel shows it. {@code rating} is the user's thumbs up (1) or down (-1) on
 * an answer, null when not rated. Token counts stay on the server: they're for cost tracking.
 */
public record MessageResponse(Long id, MessageRole role, String content, MessageStatus status,
                              Instant createdAt, Short rating) {

    public static MessageResponse from(AiMessage m, Short rating) {
        return new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getStatus(), m.getCreatedAt(), rating);
    }
}
